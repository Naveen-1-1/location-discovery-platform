package io.github.naveen11.placemap.api.routing;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.core.task.VirtualThreadTaskExecutor;

/** A routing engine for tests: each answer, a delay and the number of calls are under the test's control. */
public final class FakeRoutingClient implements RoutingClient {

    /** The production breaker's settings (resilience4j.circuitbreaker.instances.routing); RoutingConfigTest checks. */
    public static final CircuitBreakerConfig BREAKER = CircuitBreakerConfig.custom()
            .slidingWindowType(SlidingWindowType.TIME_BASED)
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .permittedNumberOfCallsInHalfOpenState(2)
            .ignoreException(new RoutingException.Unroutable())
            .build();

    public final AtomicInteger calls = new AtomicInteger();
    public volatile Duration delay = Duration.ZERO;
    public volatile Function<List<GeoPoint>, List<Leg>> travelTimes = targets -> List.of();
    public volatile Supplier<Route> route = () -> {
        throw new RoutingException(RoutingException.Kind.UNAVAILABLE, "no route configured");
    };
    public volatile Function<List<Integer>, List<Isochrone>> isochrones = minutes -> List.of();

    /** The fake behind a real guard: 8 permits, no waiting, each call on its own virtual thread. */
    public GuardedRouting guarded(CircuitBreaker breaker) {
        return new GuardedRouting(this, Bulkhead.of("routing",
                BulkheadConfig.custom().maxConcurrentCalls(8).maxWaitDuration(Duration.ZERO).build()), breaker,
                new VirtualThreadTaskExecutor("routing-call-"));
    }

    /** As {@link #guarded(CircuitBreaker)}, with the production breaker settings. */
    public GuardedRouting guarded() {
        return guarded(CircuitBreaker.of("routing", BREAKER));
    }

    /** A Refused for this reason, as the guard would throw it; production code never builds one. */
    public static GuardedRouting.Refused refused(String reason) {
        return new GuardedRouting.Refused(reason, null);
    }

    @Override
    public String name() {
        return "fake-engine";
    }

    @Override
    public List<Leg> travelTimes(GeoPoint origin, List<GeoPoint> targets, TravelMode mode) {
        pause();
        return travelTimes.apply(targets);
    }

    @Override
    public Route route(GeoPoint from, GeoPoint to, TravelMode mode) {
        pause();
        return route.get();
    }

    @Override
    public List<Isochrone> isochrones(GeoPoint origin, TravelMode mode, List<Integer> minutes) {
        pause();
        return isochrones.apply(minutes);
    }

    private void pause() {
        calls.incrementAndGet();
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
