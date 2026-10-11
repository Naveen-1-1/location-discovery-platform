package io.github.naveen11.placemap.api.places;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.naveen11.placemap.api.Deadline;
import io.github.naveen11.placemap.api.routing.GeoPoint;
import io.github.naveen11.placemap.api.routing.GuardedRouting;
import io.github.naveen11.placemap.api.routing.RoutingClient.Leg;
import io.github.naveen11.placemap.api.routing.TravelMode;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 *   <li>With a mode, the load then asks the engine for travel times to all candidates in one call, through the
 *       shared routing guard (bulkhead, circuit breaker) and cut off after {@code travelTimeTimeout}. Any failure there
 *       falls back to the straight-line order, flagged in the result.
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
    private final GuardedRouting routing;
    private final Bulkhead database;
    private final Duration travelTimeTimeout;
    private final MeterRegistry registry;
    private final ExecutorService loaders =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("nearby-load-", 1).factory());
    private final AsyncLoadingCache<NearbyQuery, NearbyResult> cache;

    public NearbyService(PlaceRepository places, GuardedRouting routing, NearbyProperties properties, Bulkhead database,
            MeterRegistry registry, Ticker ticker) {
        this.places = places;
        this.routing = routing;
        this.database = database;
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
        Integer hidden;
        try {
            Found found = database.executeSupplier(() -> new Found(
                    places.nearby(origin.lat(), origin.lon(), query.radiusMeters(), query.categories(), query.filters(),
                            CANDIDATES),
                    query.filters().openAt() == null ? null : places.unknownHours(origin.lat(), origin.lon(),
                            query.radiusMeters(), query.categories(), query.filters())));
            candidates = found.places();
            hidden = found.hiddenForUnknownHours();
        } catch (BulkheadFullException e) {
            // Expected under overload and answered with a 503: counted, not logged.
            registry.counter("placemap.nearby.rejected", "reason", "database_busy").increment();
            throw e;
        } catch (RuntimeException e) {
            log.warn("nearby search failed in the database", e);
            throw e;
        }
        if (query.mode() == null) {
            return loaded(query, STRAIGHT_LINE, null, candidates, hidden);
        }
        if (candidates.isEmpty()) {
            return loaded(query, routing.engine(), null, candidates, hidden);
        }
        List<GeoPoint> targets = candidates.stream().map(p -> new GeoPoint(p.lat(), p.lon())).toList();
        try {
            List<Leg> legs = routing.call(travelTimeTimeout,
                    engine -> engine.travelTimes(query.origin().point(), targets, query.mode()));
            return loaded(query, routing.engine(), null, byTravelTime(candidates, legs), hidden);
        } catch (GuardedRouting.Refused refused) {
            return loaded(query, STRAIGHT_LINE, refused.reason(), candidates, hidden);
        }
    }

    private record Found(List<NearbyPlace> places, Integer hiddenForUnknownHours) {
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

    private NearbyResult loaded(NearbyQuery query, String source, String fallback, List<NearbyPlace> found,
            Integer hiddenForUnknownHours) {
        String outcome = fallback != null ? fallback : query.mode() == null ? "not_requested" : "none";
        registry.counter("placemap.nearby.loads", "travel_time_source", source, "fallback", outcome).increment();
        return new NearbyResult(query, source, fallback, found, hiddenForUnknownHours);
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
