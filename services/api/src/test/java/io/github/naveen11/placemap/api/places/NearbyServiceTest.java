package io.github.naveen11.placemap.api.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.naveen11.placemap.api.Deadline;
import io.github.naveen11.placemap.api.routing.FakeRoutingClient;
import io.github.naveen11.placemap.api.routing.GuardedRouting;
import io.github.naveen11.placemap.api.routing.RoutingClient;
import io.github.naveen11.placemap.api.routing.RoutingException;
import io.github.naveen11.placemap.api.routing.RoutingException.Kind;
import io.github.naveen11.placemap.api.routing.TravelMode;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The plan's claims for showcase 3 (shared work, deadlines, bulkheads, breaker, fallback), at the service level. */
class NearbyServiceTest {

    private static final NearbyProperties DEFAULTS = new NearbyProperties(Duration.ofMillis(1500),
            Duration.ofMillis(800), 10_000, Duration.ofMinutes(5), Duration.ofSeconds(15), 10);
    private static final SnappedOrigin COMMON = new SnappedOrigin("8a2a3066...", 42.3550, -71.0656, 75);

    private final PlaceRepository places = mock(PlaceRepository.class);
    private final AtomicLong clock = new AtomicLong();
    private final FakeRoutingClient engine = new FakeRoutingClient();
    private final CircuitBreaker breaker = CircuitBreaker.of("routing", GuardedRouting.breakerConfig());
    private final GuardedRouting routing = engine.guarded(breaker);
    private NearbyService service;

    @AfterEach
    void close() {
        if (service != null) {
            service.close();
        }
        routing.close();
    }

    @Test
    void oneHundredIdenticalSearchesShareOneDatabaseQueryAndOneRoutingCall() throws Exception {
        returnPlaces(3);
        engine.travelTimes = targets -> legs(targets.size());
        engine.delay = Duration.ofMillis(200);
        service = service(DEFAULTS);
        NearbyQuery query = walk();

        var start = new CountDownLatch(1);
        var callers = new ArrayList<CompletableFuture<NearbyResult>>();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100; i++) {
                callers.add(CompletableFuture.supplyAsync(() -> {
                    await(start);
                    return service.nearby(query, Deadline.after(Duration.ofSeconds(5))).join();
                }, threads));
            }
            start.countDown();
            for (var caller : callers) {
                assertThat(caller.get().travelTimeSource()).isEqualTo("fake-engine");
            }
        }

        assertThat(engine.calls).hasValue(1);
        verify(places, times(1)).nearby(anyDouble(), anyDouble(), anyInt(), anyList(), any(PlaceFilters.class), anyInt());
    }

    @Test
    void aCallerTimingOutDoesNotCancelTheSharedWork() {
        returnPlaces(2);
        engine.travelTimes = targets -> legs(targets.size());
        engine.delay = Duration.ofMillis(300);
        service = service(DEFAULTS);
        NearbyQuery query = walk();

        assertThatThrownBy(() -> service.nearby(query, Deadline.after(Duration.ofMillis(50))).join())
                .hasCauseInstanceOf(TimeoutException.class);

        // The load went on; the next caller gets its result without a second routing call.
        NearbyResult result = service.nearby(query, Deadline.after(Duration.ofSeconds(2))).join();
        assertThat(result.travelTimeSource()).isEqualTo("fake-engine");
        assertThat(engine.calls).hasValue(1);
    }

    @Test
    void withTheDatabaseBulkheadFullTheNextSearchIsRejectedInUnderTenMilliseconds() throws Exception {
        var release = new CountDownLatch(1);
        var inside = new CountDownLatch(10);
        when(places.nearby(anyDouble(), anyDouble(), anyInt(), anyList(), any(PlaceFilters.class), anyInt())).thenAnswer(invocation -> {
            inside.countDown();
            release.await();
            return List.of();
        });
        service = service(DEFAULTS);
        for (int i = 0; i < 10; i++) {
            service.nearby(straightLine(500 + i), Deadline.after(Duration.ofSeconds(5)));
        }
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        assertThatThrownBy(() -> service.nearby(straightLine(999), Deadline.after(Duration.ofSeconds(5))).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(BulkheadFullException.class);
        long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        release.countDown();

        assertThat(millis).isLessThan(10);
    }

    @Test
    void aSlowEngineGivesAFlaggedStraightLineFallbackAtTheTravelTimeLimit() {
        returnPlaces(3);
        engine.travelTimes = targets -> legs(targets.size());
        engine.delay = Duration.ofSeconds(2);
        service = service(DEFAULTS);

        long start = System.nanoTime();
        NearbyResult result = service.nearby(walk(), Deadline.after(Duration.ofMillis(1500))).join();
        long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(result.travelTimeSource()).isEqualTo(NearbyService.STRAIGHT_LINE);
        assertThat(result.fallback()).isEqualTo("timeout");
        assertThat(result.places()).extracting(NearbyPlace::travelSeconds).containsOnlyNulls();
        assertThat(millis).isBetween(780L, 1_100L);
    }

    @Test
    void fallbacksAreSharedBrieflyAndTravelTimesForFiveMinutes() {
        returnPlaces(1);
        engine.travelTimes = targets -> {
            throw new RoutingException(Kind.UNAVAILABLE, "down");
        };
        service = service(DEFAULTS);
        NearbyQuery query = walk();

        assertThat(search(query).fallback()).isEqualTo("unavailable");
        clock.addAndGet(Duration.ofSeconds(10).toNanos());
        search(query);
        assertThat(engine.calls).as("a fallback is shared for 15 s").hasValue(1);

        engine.travelTimes = targets -> legs(targets.size());
        clock.addAndGet(Duration.ofSeconds(6).toNanos());
        assertThat(search(query).fallback()).as("then the engine is asked again").isNull();
        clock.addAndGet(Duration.ofMinutes(4).toNanos());
        search(query);
        assertThat(engine.calls).as("travel times are shared for 5 minutes").hasValue(2);
        clock.addAndGet(Duration.ofMinutes(2).toNanos());
        search(query);
        assertThat(engine.calls).hasValue(3);
    }

    @Test
    void theBreakerOpensAfterRepeatedFailuresAndStopsCallingTheEngine() {
        returnPlaces(1);
        engine.travelTimes = targets -> {
            throw new RoutingException(Kind.UNAVAILABLE, "down");
        };
        service = service(DEFAULTS);

        for (int i = 0; i < 5; i++) {
            search(walk(600 + i));
        }
        NearbyResult result = search(walk(700));

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(result.fallback()).isEqualTo("breaker_open");
        assertThat(engine.calls).hasValue(5);
    }

    @Test
    void anOriginWithNoRouteDoesNotCountAgainstTheEngine() {
        returnPlaces(1);
        engine.travelTimes = targets -> {
            throw new RoutingException(Kind.UNROUTABLE, "origin far from any path");
        };
        service = service(DEFAULTS);

        for (int i = 0; i < 6; i++) {
            assertThat(search(walk(600 + i)).fallback()).isEqualTo("unroutable");
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void placesAreSortedByTravelTimeWithUnreachableOnesLast() {
        when(places.nearby(anyDouble(), anyDouble(), anyInt(), anyList(), any(PlaceFilters.class), anyInt())).thenReturn(List.of(
                place(1, 100), place(2, 200), place(3, 300), place(4, 400)));
        engine.travelTimes = targets -> {
            var legs = new ArrayList<RoutingClient.Leg>();
            legs.add(new RoutingClient.Leg(900, 1_000)); // nearest in a straight line, but slow to reach
            legs.add(null);                              // unreachable
            legs.add(new RoutingClient.Leg(120, 300));
            legs.add(new RoutingClient.Leg(300, 500));
            return legs;
        };
        service = service(DEFAULTS);

        NearbyResult result = search(walk());

        assertThat(result.places()).extracting(NearbyPlace::id).containsExactly(3L, 4L, 1L, 2L);
        assertThat(result.places().get(0).travelSeconds()).isEqualTo(120);
        assertThat(result.places().get(3).travelSeconds()).isNull();
    }

    @Test
    void aStraightLineSearchNeverCallsTheEngine() {
        returnPlaces(2);
        service = service(DEFAULTS);

        NearbyResult result = search(straightLine(800));

        assertThat(result.travelTimeSource()).isEqualTo(NearbyService.STRAIGHT_LINE);
        assertThat(result.fallback()).isNull();
        assertThat(engine.calls).hasValue(0);
    }

    @Test
    void withOpenNowTheResultSaysHowManyPlacesHadUnknownHours() {
        returnPlaces(2);
        when(places.unknownHours(anyDouble(), anyDouble(), anyInt(), anyList(), any(PlaceFilters.class))).thenReturn(7);
        service = service(DEFAULTS);

        NearbyResult open = search(new NearbyQuery(COMMON, null, List.of(), 800, new PlaceFilters(false, false, false, 840)));
        NearbyResult plain = search(straightLine(800));

        assertThat(open.hiddenForUnknownHours()).isEqualTo(7);
        assertThat(plain.hiddenForUnknownHours()).isNull();
        verify(places, times(1)).unknownHours(anyDouble(), anyDouble(), anyInt(), anyList(), any(PlaceFilters.class));
    }

    @Test
    void theQueryKeyIgnoresTheOrderOfCategories() {
        assertThat(new NearbyQuery(COMMON, TravelMode.WALK, List.of("gym", "food", "gym"), 2_500))
                .isEqualTo(new NearbyQuery(COMMON, TravelMode.WALK, List.of("food", "gym"), 2_500));
    }

    private NearbyService service(NearbyProperties properties) {
        var config = BulkheadConfig.custom().maxConcurrentCalls(properties.databaseConcurrency())
                .maxWaitDuration(Duration.ZERO).build();
        return new NearbyService(places, routing, properties, Bulkhead.of("database", config),
                new SimpleMeterRegistry(), clock::get);
    }

    /**
     * Searches and waits until the cache has given the result its lifetime. The cache does that on the loading
     * thread just after the callers are answered, so moving the test clock any earlier would race with it.
     */
    private NearbyResult search(NearbyQuery query) {
        NearbyResult result = service.nearby(query, Deadline.after(Duration.ofSeconds(5))).join();
        long giveUp = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (service.sharedFor(query).isEmpty() && System.nanoTime() < giveUp) {
            Thread.onSpinWait();
        }
        return result;
    }

    private static NearbyQuery walk() {
        return walk(2_500);
    }

    private static NearbyQuery walk(int radius) {
        return new NearbyQuery(COMMON, TravelMode.WALK, List.of(), radius);
    }

    private static NearbyQuery straightLine(int radius) {
        return new NearbyQuery(COMMON, null, List.of(), radius);
    }

    private void returnPlaces(int count) {
        var found = new ArrayList<NearbyPlace>();
        for (int i = 1; i <= count; i++) {
            found.add(place(i, i * 100L));
        }
        when(places.nearby(anyDouble(), anyDouble(), anyInt(), anyList(), any(PlaceFilters.class), anyInt())).thenReturn(found);
    }

    private static NearbyPlace place(long id, long meters) {
        return new NearbyPlace(id, "Place " + id, "food", null, 42.355, -71.065, meters, null, null);
    }

    private static List<RoutingClient.Leg> legs(int count) {
        var legs = new ArrayList<RoutingClient.Leg>();
        for (int i = 0; i < count; i++) {
            legs.add(new RoutingClient.Leg(60L * (i + 1), 100L * (i + 1)));
        }
        return legs;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
