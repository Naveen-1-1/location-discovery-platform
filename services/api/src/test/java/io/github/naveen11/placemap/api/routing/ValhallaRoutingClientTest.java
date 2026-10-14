package io.github.naveen11.placemap.api.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.naveen11.placemap.api.routing.RoutingClient.Isochrone;
import io.github.naveen11.placemap.api.routing.RoutingClient.Leg;
import io.github.naveen11.placemap.api.routing.RoutingClient.Route;
import io.github.naveen11.placemap.api.routing.RoutingException.Kind;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The Valhalla adapter against answers recorded from our own Valhalla 3.9.0 with Massachusetts tiles
 * (src/test/resources/valhalla/, requested on 2026-10-13 from Boston Common to the Frog Pond, Harvard Square and a
 * point out at sea).
 */
class ValhallaRoutingClientTest {

    static final GeoPoint COMMON = OrsRoutingClientTest.COMMON;
    static final GeoPoint FROG_POND = OrsRoutingClientTest.FROG_POND;
    static final GeoPoint HARVARD_SQUARE = OrsRoutingClientTest.HARVARD_SQUARE;
    static final GeoPoint OUT_AT_SEA = OrsRoutingClientTest.OUT_AT_SEA;

    private static final RoutingProperties.Valhalla VALHALLA = new RoutingProperties.Valhalla(
            URI.create("http://valhalla.test"), Duration.ofMillis(800), Duration.ofSeconds(5), 350);

    private MockRestServiceServer server;
    private ValhallaRoutingClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = ValhallaRoutingClient.configure(RestClient.builder(), VALHALLA);
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient rest = builder.build();
        client = new ValhallaRoutingClient(rest, rest, VALHALLA.maxSnapMeters());
    }

    @Test
    void travelTimesLimitOnlyTheOriginsSearchAndTreatAFarMovedTargetAsUnreachable() {
        server.expect(requestTo("http://valhalla.test/sources_to_targets"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"sources": [{"lat": 42.355, "lon": -71.0656, "search_cutoff": 350.0}],
                         "targets": [{"lat": 42.3561, "lon": -71.0656}, {"lat": 42.3736, "lon": -71.119},
                                     {"lat": 42.3, "lon": -70.8}],
                         "costing": "pedestrian", "units": "kilometers", "verbose": true}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess(fixture("matrix-pedestrian.json"), MediaType.APPLICATION_JSON));

        List<Leg> legs = client.travelTimes(COMMON, List.of(FROG_POND, HARVARD_SQUARE, OUT_AT_SEA), TravelMode.WALK);

        // The point at sea was moved 955 m to an island and given a 46-minute walk (by ferry); it is unreachable.
        assertThat(legs).containsExactly(new Leg(148, 210), new Leg(3786, 5307), null);
        server.verify();
    }

    @Test
    void aTargetWithoutATimeIsUnreachable() {
        server.expect(requestTo("http://valhalla.test/sources_to_targets"))
                .andRespond(withSuccess("""
                        {"sources_to_targets": [[{"from_index": 0, "to_index": 1, "time": null, "distance": null},
                                                 {"from_index": 0, "to_index": 0, "time": 99, "distance": 0.1}]],
                         "targets": [{"lat": 42.3561, "lon": -71.0656}, {"lat": 42.3736, "lon": -71.119}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.travelTimes(COMMON, List.of(FROG_POND, HARVARD_SQUARE), TravelMode.BIKE))
                .containsExactly(new Leg(99, 100), null);
    }

    @Test
    void anOriginFarFromAnyPathFailsTheCall() {
        server.expect(requestTo("http://valhalla.test/sources_to_targets"))
                .andExpect(content().json("""
                        {"sources": [{"lat": 42.3, "lon": -70.8, "search_cutoff": 350.0}], "costing": "auto"}
                        """, JsonCompareMode.LENIENT))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body(fixture("matrix-unconnected.json")));

        assertThatThrownBy(() -> client.travelTimes(OUT_AT_SEA, List.of(COMMON), TravelMode.DRIVE))
                .isInstanceOf(RoutingException.class)
                .extracting(e -> ((RoutingException) e).kind()).isEqualTo(Kind.UNROUTABLE);
    }

    @Test
    void aMatrixWithoutTheMovedTargetsIsUnexpected() {
        server.expect(requestTo("http://valhalla.test/sources_to_targets"))
                .andRespond(withSuccess("""
                        {"sources_to_targets": [[{"from_index": 0, "to_index": 0, "time": 99, "distance": 0.1}]]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.travelTimes(COMMON, List.of(FROG_POND), TravelMode.WALK))
                .isInstanceOf(RoutingException.class)
                .extracting(e -> ((RoutingException) e).kind()).isEqualTo(Kind.UNAVAILABLE);
    }

    @Test
    void noTargetsMeansNoRequest() {
        assertThat(client.travelTimes(COMMON, List.of(), TravelMode.BIKE)).isEmpty();
        server.verify();
    }

    @Test
    void aRouteHasItsSummaryAndTheDecodedLine() {
        server.expect(requestTo("http://valhalla.test/route"))
                .andExpect(content().json("""
                        {"locations": [{"lat": 42.355, "lon": -71.0656, "search_cutoff": 350.0},
                                       {"lat": 42.3736, "lon": -71.119, "search_cutoff": 350.0}],
                         "costing": "pedestrian", "units": "kilometers", "directions_type": "none"}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess(fixture("route-pedestrian.json"), MediaType.APPLICATION_JSON));

        Route route = client.route(COMMON, HARVARD_SQUARE, TravelMode.WALK);

        assertThat(route.seconds()).isEqualTo(3786);
        assertThat(route.meters()).isEqualTo(5308);
        assertThat(route.geometry().get("type").asString()).isEqualTo("LineString");
        JsonNode line = route.geometry().get("coordinates");
        assertThat(line.size()).isEqualTo(295);
        assertThat(line.get(0).get(0).asDouble()).isEqualTo(-71.065678);
        assertThat(line.get(0).get(1).asDouble()).isEqualTo(42.355134);
        assertThat(line.get(294).get(0).asDouble()).isEqualTo(-71.119053);
        assertThat(line.get(294).get(1).asDouble()).isEqualTo(42.373617);
    }

    @Test
    void isochronesComeSmallestFirstInMinutes() {
        server.expect(requestTo("http://valhalla.test/isochrone"))
                .andExpect(content().json("""
                        {"locations": [{"lat": 42.355, "lon": -71.0656, "search_cutoff": 350.0}],
                         "costing": "pedestrian", "contours": [{"time": 5}, {"time": 10}], "polygons": true}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess(fixture("isochrone-pedestrian.json"), MediaType.APPLICATION_JSON));

        List<Isochrone> isochrones = client.isochrones(COMMON, TravelMode.WALK, List.of(10, 5));

        assertThat(isochrones).extracting(Isochrone::minutes).containsExactly(5, 10);
        assertThat(isochrones).allSatisfy(i -> assertThat(i.geometry().get("type").asString()).isEqualTo("Polygon"));
    }

    @Test
    void aRouteFromTheSeaIsUnroutableAndTheMessageHasNoCoordinates() {
        server.expect(requestTo("http://valhalla.test/route"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body(fixture("route-unroutable.json")));

        assertThatThrownBy(() -> client.route(OUT_AT_SEA, COMMON, TravelMode.WALK))
                .isInstanceOf(RoutingException.class)
                .hasMessageNotContaining("42.3")
                .extracting(e -> ((RoutingException) e).kind()).isEqualTo(Kind.UNROUTABLE);
    }

    @Test
    void statusCodesAndErrorCodesMapToKinds() {
        assertThat(ValhallaRoutingClient.failure(HttpStatus.BAD_REQUEST, 442).kind()).isEqualTo(Kind.UNROUTABLE);
        assertThat(ValhallaRoutingClient.failure(HttpStatus.BAD_REQUEST, 154).kind()).isEqualTo(Kind.REJECTED);
        assertThat(ValhallaRoutingClient.failure(HttpStatus.BAD_REQUEST, -1).kind()).isEqualTo(Kind.REJECTED);
        assertThat(ValhallaRoutingClient.failure(HttpStatus.TOO_MANY_REQUESTS, -1).kind()).isEqualTo(Kind.RATE_LIMITED);
        assertThat(ValhallaRoutingClient.failure(HttpStatus.SERVICE_UNAVAILABLE, -1).kind())
                .isEqualTo(Kind.UNAVAILABLE);
        assertThat(ValhallaRoutingClient.errorCode(stream("{\"error_code\":171,\"error\":\"x\"}"))).isEqualTo(171);
        assertThat(ValhallaRoutingClient.errorCode(stream("<html>Bad gateway</html>"))).isEqualTo(-1);
    }

    @Test
    void polylinesDecodeWithSixDecimals() {
        var coordinates = JsonNodeFactory.instance.arrayNode();
        // Google's polyline example, encoded with six decimals instead of five.
        ValhallaRoutingClient.decodePolyline6("_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI", coordinates);

        assertThat(coordinates.toString()).isEqualTo("[[-120.2,38.5],[-120.95,40.7],[-126.453,43.252]]");
    }

    @Test
    void aTimeoutIsReportedAsATimeout() {
        RestClient timingOut = RestClient.builder()
                .baseUrl("http://valhalla.test")
                .requestFactory((uri, method) -> new MockClientHttpRequest(method, uri) {
                    @Override
                    protected ClientHttpResponse executeInternal() throws IOException {
                        throw new HttpTimeoutException("request timed out");
                    }
                })
                .build();
        var slow = new ValhallaRoutingClient(timingOut, timingOut, 350);

        assertThatThrownBy(() -> slow.travelTimes(COMMON, List.of(FROG_POND), TravelMode.WALK))
                .isInstanceOf(RoutingException.class)
                .extracting(e -> ((RoutingException) e).kind()).isEqualTo(Kind.TIMEOUT);
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    static String fixture(String name) {
        try {
            return new ClassPathResource("valhalla/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
