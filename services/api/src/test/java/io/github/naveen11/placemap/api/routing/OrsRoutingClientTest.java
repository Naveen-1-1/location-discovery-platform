package io.github.naveen11.placemap.api.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.naveen11.placemap.api.routing.RoutingClient.Isochrone;
import io.github.naveen11.placemap.api.routing.RoutingClient.Leg;
import io.github.naveen11.placemap.api.routing.RoutingClient.Route;
import io.github.naveen11.placemap.api.routing.RoutingException.Kind;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The openrouteservice adapter against responses recorded from the real API (src/test/resources/ors/, requested on
 * 2026-10-11 from Boston Common to the Frog Pond, Harvard Square and a point out at sea).
 */
class OrsRoutingClientTest {

    static final GeoPoint COMMON = new GeoPoint(42.3550, -71.0656);
    static final GeoPoint FROG_POND = new GeoPoint(42.3561, -71.0656);
    static final GeoPoint HARVARD_SQUARE = new GeoPoint(42.3736, -71.1190);
    static final GeoPoint OUT_AT_SEA = new GeoPoint(42.30, -70.80);

    private static final RoutingProperties.Ors ORS = new RoutingProperties.Ors(URI.create("https://ors.test"),
            "test-key", Duration.ofSeconds(1), Duration.ofSeconds(5), 350);

    private MockRestServiceServer server;
    private OrsRoutingClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = OrsRoutingClient.configure(RestClient.builder(), ORS);
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient rest = builder.build();
        client = new OrsRoutingClient(rest, rest, ORS.maxSnapMeters());
    }

    @Test
    void travelTimesAskForOneOriginAndTreatAFarSnappedTargetAsUnreachable() {
        server.expect(requestTo("https://ors.test/v2/matrix/foot-walking"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "test-key"))
                .andExpect(content().json("""
                        {"locations": [[-71.0656, 42.355], [-71.0656, 42.3561], [-71.119, 42.3736], [-70.8, 42.3]],
                         "sources": [0], "destinations": [1, 2, 3], "metrics": ["duration", "distance"], "units": "m"}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess(fixture("matrix-foot-walking.json"), MediaType.APPLICATION_JSON));

        List<Leg> legs = client.travelTimes(COMMON, List.of(FROG_POND, HARVARD_SQUARE, OUT_AT_SEA), TravelMode.WALK);

        // The point at sea was moved 954 m to an island and given a six-hour walk; it is unreachable.
        assertThat(legs).containsExactly(new Leg(150, 209), new Leg(3814, 5297), null);
        server.verify();
    }

    @Test
    void anOriginFarFromAnyPathFailsTheCall() {
        server.expect(requestTo("https://ors.test/v2/matrix/driving-car"))
                .andRespond(withSuccess("""
                        {"durations": [[100.0]], "distances": [[900.0]],
                         "sources": [{"location": [-70.8, 42.3], "snapped_distance": 954.5}],
                         "destinations": [{"location": [-71.06, 42.35], "snapped_distance": 3.0}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.travelTimes(OUT_AT_SEA, List.of(COMMON), TravelMode.DRIVE))
                .isInstanceOf(RoutingException.class)
                .extracting(e -> ((RoutingException) e).kind()).isEqualTo(Kind.UNROUTABLE);
    }

    @Test
    void noTargetsMeansNoRequest() {
        assertThat(client.travelTimes(COMMON, List.of(), TravelMode.BIKE)).isEmpty();
        server.verify();
    }

    @Test
    void aRouteHasItsSummaryAndLine() {
        server.expect(requestTo("https://ors.test/v2/directions/foot-walking/geojson"))
                .andExpect(content().json("""
                        {"coordinates": [[-71.0656, 42.355], [-71.119, 42.3736]], "instructions": false}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess(fixture("directions-foot-walking.json"), MediaType.APPLICATION_JSON));

        Route route = client.route(COMMON, HARVARD_SQUARE, TravelMode.WALK);

        assertThat(route.seconds()).isEqualTo(3814);
        assertThat(route.meters()).isEqualTo(5297);
        assertThat(route.geometry().get("type").asString()).isEqualTo("LineString");
        assertThat(route.geometry().get("coordinates").size()).isEqualTo(240);
    }

    @Test
    void isochronesComeSmallestFirstInMinutes() {
        server.expect(requestTo("https://ors.test/v2/isochrones/foot-walking"))
                .andExpect(content().json("""
                        {"locations": [[-71.0656, 42.355]], "range": [300, 600], "range_type": "time"}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess(fixture("isochrones-foot-walking.json"), MediaType.APPLICATION_JSON));

        List<Isochrone> isochrones = client.isochrones(COMMON, TravelMode.WALK, List.of(10, 5));

        assertThat(isochrones).extracting(Isochrone::minutes).containsExactly(5, 10);
        assertThat(isochrones).allSatisfy(i -> assertThat(i.geometry().get("type").asString()).isEqualTo("Polygon"));
    }

    @Test
    void aRouteFromTheSeaIsUnroutableAndTheMessageHasNoCoordinates() {
        server.expect(requestTo("https://ors.test/v2/directions/foot-walking/geojson"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body(fixture("directions-unroutable.json")));

        assertThatThrownBy(() -> client.route(OUT_AT_SEA, COMMON, TravelMode.WALK))
                .isInstanceOf(RoutingException.class)
                .hasMessageNotContaining("42.3")
                .extracting(e -> ((RoutingException) e).kind()).isEqualTo(Kind.UNROUTABLE);
    }

    @Test
    void statusCodesMapToKinds() {
        assertThat(OrsRoutingClient.failure(HttpStatus.TOO_MANY_REQUESTS).kind()).isEqualTo(Kind.RATE_LIMITED);
        assertThat(OrsRoutingClient.failure(HttpStatus.SERVICE_UNAVAILABLE).kind()).isEqualTo(Kind.UNAVAILABLE);
        assertThat(OrsRoutingClient.failure(HttpStatus.FORBIDDEN).kind()).isEqualTo(Kind.REJECTED);
        assertThat(OrsRoutingClient.failure(HttpStatus.BAD_REQUEST).kind()).isEqualTo(Kind.REJECTED);
    }

    @Test
    void aTimeoutIsReportedAsATimeout() {
        RestClient timingOut = RestClient.builder()
                .baseUrl("https://ors.test")
                .requestFactory((uri, method) -> new MockClientHttpRequest(method, uri) {
                    @Override
                    protected ClientHttpResponse executeInternal() throws IOException {
                        throw new HttpTimeoutException("request timed out");
                    }
                })
                .build();
        var slow = new OrsRoutingClient(timingOut, timingOut, 350);

        assertThatThrownBy(() -> slow.travelTimes(COMMON, List.of(FROG_POND), TravelMode.WALK))
                .isInstanceOf(RoutingException.class)
                .extracting(e -> ((RoutingException) e).kind()).isEqualTo(Kind.TIMEOUT);
    }

    static String fixture(String name) {
        try {
            return new ClassPathResource("ors/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
