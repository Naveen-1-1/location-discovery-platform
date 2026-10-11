package io.github.naveen11.placemap.api.places;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.naveen11.placemap.api.Deadline;
import io.github.naveen11.placemap.api.routing.GeoPoint;
import io.github.naveen11.placemap.api.routing.RoutingClient;
import io.github.naveen11.placemap.api.routing.RoutingClient.Leg;
import io.github.naveen11.placemap.api.routing.RoutingException;
import io.github.naveen11.placemap.api.routing.TravelMode;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nearby search with travel times (plan: showcase 3; ADR 0025, 0026).
 *
 * <ol>
 *   <li>The query (snapped cell, mode, categories, radius) keys an {@link AsyncLoadingCache}: concurrent identical
 *       searches share one load, which runs on a virtual thread. Each caller waits on a copy of the shared future with
 *       its own deadline, so a caller timing out never cancels the work the others wait for.
 *   <li>A load passes the database bulkhead without waiting (full means the caller gets a 503) and fetches the
 *       {@value #CANDIDATES} nearest candidates.
 *   <li>With a mode, the load then passes the routing bulkhead and circuit breaker and asks the engine for travel
 *       times to all candidates in one call, cut off after {@code travelTimeTimeout}. Any failure there falls back to
 *       the straight-line order, flagged in the result.
 *   <li>Failed loads are not cached. Results with travel times are shared for {@code cacheTtl}, fallbacks for the
 *       much shorter {@code fallbackTtl}.
 * </ol>
 */
public class NearbyService implements AutoCloseable {

    /** Candidates fetched from the database and sent to the routing engine in one call (the plan's 1×200). */
    public static final int CANDIDATES = 200;

    public static final String STRAIGHT_LINE = "straight_line";

    private static final Logger log = LoggerFactory.getLogger(NearbyService.class);

    private final PlaceRepository places;
    private final RoutingClient routing;
    private final Bulkhead database;
    private final Bulkhead routingBulkhead;
    private final CircuitBreaker breaker;
    private final Duration travelTimeTimeout;
    private final MeterRegistry registry;
    private final ExecutorService loaders =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("nearby-load-", 1).factory());
    private final AsyncLoadingCache<NearbyQuery, NearbyResult> cache;

    public NearbyService(PlaceRepository places, RoutingClient routing, NearbyProperties properties, Bulkhead database,
            Bulkhead routingBulkhead, CircuitBreaker breaker, MeterRegistry registry, Ticker ticker) {
        this.places = places;
        this.routing = routing;
        this.database = database;
        this.routingBulkhead = routingBulkhead;
        this.breaker = breaker;
        this.travelTimeTimeout = properties.travelTimeTimeout();
        this.registry = registry;
        // Loads run on virtual threads; the cache's own housekeeping stays on its default pool, which outlives this
        // service during shutdown.
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.cacheSize())
                .expireAfter(new ResultExpiry(properties.cacheTtl(), properties.fallbackTtl()))
                .ticker(ticker)
                .recordStats()
                .buildAsync((query, ignored) -> CompletableFuture.supplyAsync(() -> load(query), loaders));
        CaffeineCacheMetrics.monitor(registry, cache, "nearby");
    }

    /** How far each mode searches for candidates (plan: walk 2.5 km, bike 8 km, drive 20 km). */
    public static int searchRadius(TravelMode mode) {
        return switch (mode) {
            case WALK -> 2_500;
            case BIKE -> 8_000;
            case DRIVE -> 20_000;
        };
    }

    /**
     * The shared result for the query. The future fails with {@link TimeoutException} at the deadline, and with the
     * bulkhead's exception when the database already has all the searches it can take.
     */
    public CompletableFuture<NearbyResult> nearby(NearbyQuery query, Deadline deadline) {
        return cache.get(query).copy().orTimeout(deadline.remaining().toNanos(), TimeUnit.NANOSECONDS);
    }

    private NearbyResult load(NearbyQuery query) {
        SnappedOrigin origin = query.origin();
        List<NearbyPlace> candidates;
        try {
            candidates = database.executeSupplier(() -> places.nearby(origin.lat(), origin.lon(), query.radiusMeters(),
                    query.categories(), CANDIDATES));
        } catch (BulkheadFullException e) {
            // Expected under overload and answered with a 503: counted, not logged.
            registry.counter("placemap.nearby.rejected", "reason", "database_busy").increment();
            throw e;
        } catch (RuntimeException e) {
            log.warn("nearby search failed in the database", e);
            throw e;
        }
        if (query.mode() == null) {
            return loaded(query, STRAIGHT_LINE, null, candidates);
        }
        if (candidates.isEmpty()) {
            return loaded(query, routing.name(), null, candidates);
        }
        try {
            return loaded(query, routing.name(), null, byTravelTime(candidates, travelTimes(query, candidates)));
        } catch (Fallback f) {
            return loaded(query, STRAIGHT_LINE, f.getMessage(), candidates);
        }
    }

    private List<Leg> travelTimes(NearbyQuery query, List<NearbyPlace> candidates) {
        if (!routingBulkhead.tryAcquirePermission()) {
            throw new Fallback("bulkhead_full");
        }
        try {
            if (!breaker.tryAcquirePermission()) {
                throw new Fallback("breaker_open");
            }
            List<GeoPoint> targets = candidates.stream().map(p -> new GeoPoint(p.lat(), p.lon())).toList();
            long start = System.nanoTime();
            try {
                // The time limit is enforced here, whatever the adapter's own HTTP timeout: a late answer is
                // abandoned (and still logged by the client), the fallback goes out on time.
                List<Leg> legs = CompletableFuture
                        .supplyAsync(() -> routing.travelTimes(query.origin().point(), targets, query.mode()), loaders)
                        .orTimeout(travelTimeTimeout.toNanos(), TimeUnit.NANOSECONDS)
                        .join();
                breaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                return legs;
            } catch (CompletionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, cause);
                throw new Fallback(reason(cause));
            }
        } finally {
            routingBulkhead.onComplete();
        }
    }

    static List<NearbyPlace> byTravelTime(List<NearbyPlace> candidates, List<Leg> legs) {
        var timed = new ArrayList<NearbyPlace>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            timed.add(candidates.get(i).withTravel(legs.get(i)));
        }
        // Places the engine cannot reach go last, in straight-line order.
        timed.sort(Comparator.comparing(NearbyPlace::travelSeconds, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingLong(NearbyPlace::distanceMeters)
                .thenComparingLong(NearbyPlace::id));
        return List.copyOf(timed);
    }

    static String reason(Throwable cause) {
        if (cause instanceof TimeoutException) {
            return "timeout";
        }
        if (cause instanceof RoutingException routingFailure) {
            return routingFailure.kind().name().toLowerCase(Locale.ROOT);
        }
        return "error";
    }

    private NearbyResult loaded(NearbyQuery query, String source, String fallback, List<NearbyPlace> found) {
        String outcome = fallback != null ? fallback : query.mode() == null ? "not_requested" : "none";
        registry.counter("placemap.nearby.loads", "travel_time_source", source, "fallback", outcome).increment();
        return new NearbyResult(query, source, fallback, found);
    }

    /** How long the query's result stays shared; empty while it is still loading or once it is gone. For tests. */
    Optional<Duration> sharedFor(NearbyQuery query) {
        return cache.synchronous().policy().expireVariably()
                .flatMap(expiry -> expiry.getExpiresAfter(query))
                .filter(left -> left.compareTo(Duration.ofDays(1)) < 0);
    }

    @Override
    public void close() {
        loaders.close();
    }

    /** Ends a load's travel-time step; the message is the reason reported to clients. No stack trace needed. */
    private static final class Fallback extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Fallback(String reason) {
            super(reason, null, false, false);
        }
    }

    /** Routed results live for {@code routed}, fallbacks for {@code fallback}; reads do not extend either. */
    private record ResultExpiry(Duration routed, Duration fallback) implements Expiry<NearbyQuery, NearbyResult> {

        @Override
        public long expireAfterCreate(NearbyQuery key, NearbyResult value, long currentTime) {
            return (value.fallback() == null ? routed : fallback).toNanos();
        }

        @Override
        public long expireAfterUpdate(NearbyQuery key, NearbyResult value, long currentTime, long currentDuration) {
            return expireAfterCreate(key, value, currentTime);
        }

        @Override
        public long expireAfterRead(NearbyQuery key, NearbyResult value, long currentTime, long currentDuration) {
            return currentDuration;
        }
    }
}
