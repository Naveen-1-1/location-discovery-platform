package io.github.naveen11.placemap.api.directions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.naveen11.placemap.api.Deadline;
import io.github.naveen11.placemap.api.directions.DirectionsService.IsochroneKey;
import io.github.naveen11.placemap.api.directions.DirectionsService.RouteKey;
import io.github.naveen11.placemap.api.places.PlaceLocation;
import io.github.naveen11.placemap.api.places.PlaceRepository;
import io.github.naveen11.placemap.api.places.SnappedOrigin;
import io.github.naveen11.placemap.api.routing.FakeRoutingClient;
import io.github.naveen11.placemap.api.routing.GuardedRouting;
import io.github.naveen11.placemap.api.routing.RoutingClient.Isochrone;
import io.github.naveen11.placemap.api.routing.RoutingClient.Route;
import io.github.naveen11.placemap.api.routing.RoutingException;
import io.github.naveen11.placemap.api.routing.TravelMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DirectionsServiceTest {

    private static final SnappedOrigin COMMON = new SnappedOrigin("8a2a3066...", 42.3550, -71.0656, 75);
    private static final DirectionsProperties DEFAULTS = new DirectionsProperties(Duration.ofSeconds(6),
            Duration.ofSeconds(5), Duration.ofMinutes(10), Duration.ofMinutes(30), 1_000, 30);

    private final PlaceRepository places = mock(PlaceRepository.class);
    private final FakeRoutingClient engine = new FakeRoutingClient();
    private final GuardedRouting routing = engine.guarded();
    private final DirectionsService directions = new DirectionsService(places, routing, DEFAULTS,
            new SimpleMeterRegistry());

    @AfterEach
    void close() {
        directions.close();
        routing.close();
    }

    @Test
    void identicalRouteRequestsShareOneEngineCall() throws Exception {
        when(places.location(7)).thenReturn(Optional.of(new PlaceLocation(7, "Tatte", 42.356, -71.066)));
        engine.delay = Duration.ofMillis(100);
        engine.route = () -> new Route(240, 310, JsonMapper.shared().readTree("""
                {"type": "LineString", "coordinates": [[-71.0656, 42.355], [-71.066, 42.356]]}"""));

        var waiting = new ArrayList<CompletableFuture<DirectionsService.RouteResult>>();
        for (int i = 0; i < 20; i++) {
            waiting.add(directions.route(new RouteKey(COMMON, 7, TravelMode.WALK), deadline()));
        }

        for (var result : waiting) {
            assertThat(result.get().route().seconds()).isEqualTo(240);
            assertThat(result.get().place().name()).isEqualTo("Tatte");
        }
        assertThat(engine.calls).hasValue(1);
    }

    @Test
    void aRouteToAnUnknownPlaceFailsWithoutCallingTheEngine() {
        when(places.location(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> directions.route(new RouteKey(COMMON, 404, TravelMode.WALK), deadline()).join())
                .hasCauseInstanceOf(DirectionsService.UnknownPlace.class)
                .hasMessageContaining("No place with id 404");
        assertThat(engine.calls).hasValue(0);
    }

    @Test
    void noRouteIsReportedAsUnroutable() {
        when(places.location(7)).thenReturn(Optional.of(new PlaceLocation(7, "On an island", 42.30, -70.95)));
        engine.route = () -> {
            throw new RoutingException(RoutingException.Kind.UNROUTABLE, "no route");
        };

        assertThatThrownBy(() -> directions.route(new RouteKey(COMMON, 7, TravelMode.DRIVE), deadline()).join())
                .cause()
                .isInstanceOfSatisfying(GuardedRouting.Refused.class, r -> assertThat(r.unroutable()).isTrue());
    }

    @Test
    void isochronesAreSharedWhateverTheOrderOfTheLimits() {
        engine.isochrones = minutes -> minutes.stream().map(m -> new Isochrone(m, null)).toList();

        var first = directions.isochrones(new IsochroneKey(COMMON, TravelMode.BIKE, List.of(15, 5, 10)), deadline())
                .join();
        var second = directions.isochrones(new IsochroneKey(COMMON, TravelMode.BIKE, List.of(5, 10, 15, 10)),
                deadline()).join();

        assertThat(first.areas()).extracting(Isochrone::minutes).containsExactly(5, 10, 15);
        assertThat(second).isSameAs(first);
        assertThat(engine.calls).hasValue(1);
    }

    @Test
    void aFailedRouteIsNotKept() {
        when(places.location(7)).thenReturn(Optional.of(new PlaceLocation(7, "Tatte", 42.356, -71.066)));
        engine.route = () -> {
            throw new RoutingException(RoutingException.Kind.UNAVAILABLE, "down");
        };
        assertThatThrownBy(() -> directions.route(new RouteKey(COMMON, 7, TravelMode.WALK), deadline()).join())
                .hasCauseInstanceOf(GuardedRouting.Refused.class);

        engine.route = () -> new Route(60, 80, null);

        assertThat(directions.route(new RouteKey(COMMON, 7, TravelMode.WALK), deadline()).join().route().seconds())
                .isEqualTo(60);
        assertThat(engine.calls).hasValue(2);
    }

    private static Deadline deadline() {
        return Deadline.after(Duration.ofSeconds(5));
    }
}
