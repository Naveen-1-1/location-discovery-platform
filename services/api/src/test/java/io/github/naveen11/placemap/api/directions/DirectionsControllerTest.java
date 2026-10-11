package io.github.naveen11.placemap.api.directions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.naveen11.placemap.api.directions.DirectionsService.IsochroneKey;
import io.github.naveen11.placemap.api.directions.DirectionsService.IsochroneResult;
import io.github.naveen11.placemap.api.directions.DirectionsService.RouteResult;
import io.github.naveen11.placemap.api.places.OriginSnapper;
import io.github.naveen11.placemap.api.places.PlaceLocation;
import io.github.naveen11.placemap.api.routing.FakeRoutingClient;
import io.github.naveen11.placemap.api.routing.RoutingClient.Isochrone;
import io.github.naveen11.placemap.api.routing.RoutingClient.Route;
import io.github.naveen11.placemap.api.routing.TravelMode;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(DirectionsController.class)
@Import({OriginSnapper.class, DirectionsControllerTest.Properties.class})
class DirectionsControllerTest {

    @TestConfiguration
    @EnableConfigurationProperties(DirectionsProperties.class)
    static class Properties {
    }

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    DirectionsService directions;

    @Test
    void aRouteIsAGeoJsonFeature() {
        var line = JsonMapper.shared().readTree("""
                {"type": "LineString", "coordinates": [[-71.0656, 42.355], [-71.066, 42.356]]}""");
        when(directions.route(any(), any())).thenReturn(CompletableFuture.completedFuture(new RouteResult(
                new PlaceLocation(7, "Tatte", 42.356, -71.066), new Route(240, 310, line), "openrouteservice")));

        var result = mvc.get().uri("/api/route?lat=42.355&lon=-71.0656&place=7&mode=drive").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo("Feature");
        assertThat(result).bodyJson().extractingPath("$.geometry.type").isEqualTo("LineString");
        assertThat(result).bodyJson().extractingPath("$.properties.placeName").isEqualTo("Tatte");
        assertThat(result).bodyJson().extractingPath("$.properties.seconds").isEqualTo(240);
        assertThat(result).bodyJson().extractingPath("$.properties.driveWithoutTraffic").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.properties.travelTimeSource").isEqualTo("openrouteservice");
    }

    @Test
    void isochronesAreAFeatureCollectionForFiveTenAndFifteenMinutesByDefault() {
        when(directions.isochrones(any(), any())).thenAnswer(invocation -> {
            IsochroneKey key = invocation.getArgument(0);
            return CompletableFuture.completedFuture(new IsochroneResult(
                    key.minutes().stream().map(m -> new Isochrone(m, null)).toList(), "openrouteservice"));
        });

        var result = mvc.get().uri("/api/isochrone?lat=42.355&lon=-71.0656").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo("FeatureCollection");
        assertThat(result).bodyJson().extractingPath("$.mode").isEqualTo("walk");
        assertThat(result).bodyJson().extractingPath("$.features[*].properties.minutes").isEqualTo(List.of(5, 10, 15));
        var key = ArgumentCaptor.forClass(IsochroneKey.class);
        verify(directions).isochrones(key.capture(), any());
        assertThat(key.getValue().mode()).isEqualTo(TravelMode.WALK);
    }

    @Test
    void rejectsLimitsOutsideOneToThirtyMinutesOrMoreThanThree() {
        for (String minutes : List.of("0", "31", "5,10,15,20")) {
            assertThat(mvc.get().uri("/api/isochrone?lat=42.355&lon=-71.0656&minutes=" + minutes).exchange())
                    .as(minutes).hasStatus(HttpStatus.BAD_REQUEST);
        }
        verify(directions, never()).isochrones(any(), any());
    }

    @Test
    void anUnknownPlaceIs404() {
        when(directions.route(any(), any())).thenReturn(CompletableFuture.failedFuture(
                new DirectionsService.UnknownPlace(404)));

        var result = mvc.get().uri("/api/route?lat=42.355&lon=-71.0656&place=404").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("No place with id 404");
    }

    @Test
    void noRouteIs404() {
        when(directions.route(any(), any())).thenReturn(CompletableFuture.failedFuture(
                FakeRoutingClient.refused("unroutable")));

        var result = mvc.get().uri("/api/route?lat=42.30&lon=-70.80&place=7&mode=bike").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.detail")
                .isEqualTo("No bike route found from here to this place.");
    }

    @Test
    void anOpenBreakerIs503WithALongerRetryAfter() {
        when(directions.isochrones(any(), any())).thenReturn(CompletableFuture.failedFuture(
                FakeRoutingClient.refused("breaker_open")));

        assertThat(mvc.get().uri("/api/isochrone?lat=42.355&lon=-71.0656").exchange())
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .hasHeader(HttpHeaders.RETRY_AFTER, "10");
    }

    @Test
    void aMissedDeadlineIs503() {
        when(directions.route(any(), any())).thenReturn(CompletableFuture.failedFuture(new TimeoutException()));

        assertThat(mvc.get().uri("/api/route?lat=42.355&lon=-71.0656&place=7").exchange())
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .hasHeader(HttpHeaders.RETRY_AFTER, "5");
    }

    @Test
    void rejectsAnUnknownMode() {
        assertThat(mvc.get().uri("/api/route?lat=42.355&lon=-71.0656&place=7&mode=fly").exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
    }
}
