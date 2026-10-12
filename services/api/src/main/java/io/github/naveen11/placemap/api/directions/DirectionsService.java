package io.github.naveen11.placemap.api.directions;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.naveen11.placemap.api.Deadline;
import io.github.naveen11.placemap.api.places.PlaceLocation;
import io.github.naveen11.placemap.api.places.PlaceRepository;
import io.github.naveen11.placemap.api.places.SnappedOrigin;
import io.github.naveen11.placemap.api.routing.GeoPoint;
import io.github.naveen11.placemap.api.routing.GuardedRouting;
import io.github.naveen11.placemap.api.routing.RoutingClient.Isochrone;
import io.github.naveen11.placemap.api.routing.RoutingClient.Route;
import io.github.naveen11.placemap.api.routing.TravelMode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Routes and isochrones from a snapped origin (plan: showcase 3). As with nearby, answers are shared: identical
 * requests from one cell wait on one engine call, and the answer is kept a while (routes {@code routeTtl},
 * isochrones {@code isochroneTtl}), which spares openrouteservice's daily quotas. Failures are not kept.
 */
public class DirectionsService implements AutoCloseable {

    public record RouteKey(SnappedOrigin origin, long placeId, TravelMode mode) {}

    /** @param minutes sorted and without repeats, so the order a client lists them in does not matter */
    public record IsochroneKey(SnappedOrigin origin, TravelMode mode, List<Integer> minutes) {

        public IsochroneKey {
            minutes = minutes.stream().distinct().sorted().toList();
        }
    }

    public record RouteResult(PlaceLocation place, Route route, String travelTimeSource) {}

    /** @param areas smallest first */
    public record IsochroneResult(List<Isochrone> areas, String travelTimeSource) {}

    /** The route's destination does not exist or has been retired. */
    public static final class UnknownPlace extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnknownPlace(long placeId) {
            super("No place with id " + placeId, null, false, false);
        }
    }

    private final PlaceRepository places;
    private final GuardedRouting routing;
    private final Duration timeout;
    private final ExecutorService loaders =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("directions-load-", 1).factory());
    private final AsyncLoadingCache<RouteKey, RouteResult> routes;
    private final AsyncLoadingCache<IsochroneKey, IsochroneResult> isochrones;

    public DirectionsService(PlaceRepository places, GuardedRouting routing, DirectionsProperties properties,
            MeterRegistry registry) {
        this.places = places;
        this.routing = routing;
        this.timeout = properties.timeout();
        this.routes = Caffeine.newBuilder()
                .maximumSize(properties.cacheSize())
                .expireAfterWrite(properties.routeTtl())
                .recordStats()
                .buildAsync((key, ignored) -> CompletableFuture.supplyAsync(() -> loadRoute(key), loaders));
        this.isochrones = Caffeine.newBuilder()
                .maximumSize(properties.cacheSize())
                .expireAfterWrite(properties.isochroneTtl())
                .recordStats()
                .buildAsync((key, ignored) -> CompletableFuture.supplyAsync(() -> loadIsochrones(key), loaders));
        CaffeineCacheMetrics.monitor(registry, routes, "routes");
        CaffeineCacheMetrics.monitor(registry, isochrones, "isochrones");
    }

    /**
     * The shared route. The future fails with {@link UnknownPlace}, {@link GuardedRouting.Refused} when the engine
     * gave no route, or a {@link java.util.concurrent.TimeoutException} at the deadline.
     */
    public CompletableFuture<RouteResult> route(RouteKey key, Deadline deadline) {
        return routes.get(key).copy().orTimeout(deadline.remaining().toNanos(), TimeUnit.NANOSECONDS);
    }

    /** The shared isochrones; fails like {@link #route}, except that there is no place to be unknown. */
    public CompletableFuture<IsochroneResult> isochrones(IsochroneKey key, Deadline deadline) {
        return isochrones.get(key).copy().orTimeout(deadline.remaining().toNanos(), TimeUnit.NANOSECONDS);
    }

    private RouteResult loadRoute(RouteKey key) {
        PlaceLocation place = places.location(key.placeId()).orElseThrow(() -> new UnknownPlace(key.placeId()));
        GeoPoint to = new GeoPoint(place.lat(), place.lon());
        Route route = routing.call(timeout, engine -> engine.route(key.origin().point(), to, key.mode()));
        return new RouteResult(place, route, routing.engine());
    }

    private IsochroneResult loadIsochrones(IsochroneKey key) {
        List<Isochrone> areas = routing.call(timeout,
                engine -> engine.isochrones(key.origin().point(), key.mode(), key.minutes()));
        return new IsochroneResult(areas, routing.engine());
    }

    @Override
    public void close() {
        loaders.close();
    }
}
