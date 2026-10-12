package io.github.naveen11.placemap.api.routing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.naveen11.placemap.api.routing.RoutingException.Kind;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * {@link RoutingClient} for the hosted openrouteservice API v2: its matrix, directions and isochrones
 * endpoints, with the API key in the Authorization header.
 *
 * <p>The matrix endpoint moves every location to the nearest path, however far away it is: a point out at sea came
 * back as a six-hour walk from the nearest island. So a target moved further than {@code maxSnapMeters} counts as
 * unreachable, and an origin moved that far fails the whole call.
 *
 * <p>Error messages never include the coordinates openrouteservice echoes back, because they end up in logs.
 */
public final class OrsRoutingClient implements RoutingClient {

    /** openrouteservice allows 3,500 origin × destination pairs per matrix request. */
    static final int MAX_TARGETS = 3_499;

    private final RestClient matrix;
    private final RestClient slow;
    private final double maxSnapMeters;

    /**
     * @param matrix the client for travel times, with a timeout that fits nearby's deadline
     * @param slow the client for routes and isochrones
     */
    public OrsRoutingClient(RestClient matrix, RestClient slow, double maxSnapMeters) {
        this.matrix = matrix;
        this.slow = slow;
        this.maxSnapMeters = maxSnapMeters;
    }

    /** The base URL, API key and accepted types both clients share. */
    public static RestClient.Builder configure(RestClient.Builder builder, RoutingProperties.Ors ors) {
        return builder.baseUrl(ors.baseUrl().toString())
                .defaultHeader(HttpHeaders.AUTHORIZATION, ors.apiKey())
                .defaultHeader(HttpHeaders.ACCEPT, "application/json, application/geo+json");
    }

    @Override
    public String name() {
        return "openrouteservice";
    }

    @Override
    public List<Leg> travelTimes(GeoPoint origin, List<GeoPoint> targets, TravelMode mode) {
        if (targets.isEmpty()) {
            return List.of();
        }
        if (targets.size() > MAX_TARGETS) {
            throw new IllegalArgumentException("at most " + MAX_TARGETS + " targets per call, got " + targets.size());
        }
        var locations = new ArrayList<double[]>(targets.size() + 1);
        locations.add(origin.lonLat());
        targets.forEach(t -> locations.add(t.lonLat()));
        var request = new MatrixRequest(locations, List.of(0),
                IntStream.rangeClosed(1, targets.size()).boxed().toList(), List.of("duration", "distance"), "m");
        MatrixResponse response = post(matrix, "/v2/matrix/{profile}", mode, request, MatrixResponse.class);

        if (response.sources() == null || response.sources().size() != 1 || response.durations() == null
                || response.distances() == null || response.durations().size() != 1
                || response.distances().size() != 1) {
            throw new RoutingException(Kind.UNAVAILABLE, "openrouteservice returned an unexpected matrix");
        }
        Snapped from = response.sources().getFirst();
        if (from == null || !closeEnough(from)) {
            throw new RoutingException(Kind.UNROUTABLE, "the origin is further than " + maxSnapMeters + " m from a path");
        }
        List<Double> durations = response.durations().getFirst();
        List<Double> distances = response.distances().getFirst();
        List<Snapped> snapped = response.destinations();
        if (durations.size() != targets.size() || distances.size() != targets.size() || snapped == null
                || snapped.size() != targets.size()) {
            throw new RoutingException(Kind.UNAVAILABLE, "openrouteservice returned an unexpected matrix");
        }
        var legs = new ArrayList<Leg>(targets.size());
        for (int i = 0; i < targets.size(); i++) {
            Double seconds = durations.get(i);
            Double meters = distances.get(i);
            Snapped to = snapped.get(i);
            boolean reachable = seconds != null && meters != null && to != null && closeEnough(to);
            legs.add(reachable ? new Leg(Math.round(seconds), Math.round(meters)) : null);
        }
        return Collections.unmodifiableList(legs);
    }

    @Override
    public Route route(GeoPoint from, GeoPoint to, TravelMode mode) {
        var request = new DirectionsRequest(List.of(from.lonLat(), to.lonLat()), false);
        FeatureCollection response = post(slow, "/v2/directions/{profile}/geojson", mode, request,
                FeatureCollection.class);
        Feature route = response.features() == null || response.features().isEmpty()
                ? null : response.features().getFirst();
        if (route == null || route.geometry() == null) {
            throw new RoutingException(Kind.UNAVAILABLE, "openrouteservice returned no route");
        }
        // The summary leaves out zero values, as for a route to the starting point itself.
        Summary summary = route.properties() == null || route.properties().summary() == null
                ? new Summary(null, null) : route.properties().summary();
        return new Route(round(summary.duration()), round(summary.distance()), route.geometry());
    }

    @Override
    public List<Isochrone> isochrones(GeoPoint origin, TravelMode mode, List<Integer> minutes) {
        if (minutes.isEmpty()) {
            return List.of();
        }
        List<Integer> seconds = minutes.stream().distinct().sorted().map(m -> m * 60).toList();
        var request = new IsochronesRequest(List.of(origin.lonLat()), seconds, "time");
        FeatureCollection response = post(slow, "/v2/isochrones/{profile}", mode, request, FeatureCollection.class);
        if (response.features() == null || response.features().size() != seconds.size()) {
            throw new RoutingException(Kind.UNAVAILABLE, "openrouteservice returned unexpected isochrones");
        }
        return response.features().stream()
                .filter(f -> f.properties() != null && f.properties().value() != null && f.geometry() != null)
                .sorted(Comparator.comparingDouble(f -> f.properties().value()))
                .map(f -> new Isochrone((int) Math.round(f.properties().value() / 60), f.geometry()))
                .toList();
    }

    private boolean closeEnough(Snapped snapped) {
        return snapped.snappedDistance() == null || snapped.snappedDistance() <= maxSnapMeters;
    }

    static String profile(TravelMode mode) {
        return switch (mode) {
            case WALK -> "foot-walking";
            case BIKE -> "cycling-regular";
            case DRIVE -> "driving-car";
        };
    }

    private static <T> T post(RestClient client, String path, TravelMode mode, Object body, Class<T> type) {
        T result;
        try {
            result = client.post()
                    .uri(path, profile(mode))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw failure(response.getStatusCode());
                    })
                    .body(type);
        } catch (ResourceAccessException e) {
            boolean timedOut = causes(e).anyMatch(c -> c instanceof HttpTimeoutException
                    || c instanceof SocketTimeoutException);
            throw timedOut
                    ? new RoutingException(Kind.TIMEOUT, "openrouteservice did not answer in time", e)
                    : new RoutingException(Kind.UNAVAILABLE, "openrouteservice is unreachable", e);
        } catch (RestClientException e) {
            throw new RoutingException(Kind.UNAVAILABLE, "unreadable answer from openrouteservice", e);
        }
        if (result == null) {
            throw new RoutingException(Kind.UNAVAILABLE, "empty answer from openrouteservice");
        }
        return result;
    }

    static RoutingException failure(HttpStatusCode status) {
        int code = status.value();
        return switch (code) {
            case 429 -> new RoutingException(Kind.RATE_LIMITED, "openrouteservice quota or rate limit reached");
            // 2009 "route could not be found" and 2010 "point not found" both come as 404.
            case 404 -> new RoutingException(Kind.UNROUTABLE, "openrouteservice found no route");
            case 401, 403 -> new RoutingException(Kind.REJECTED, "openrouteservice refused the API key (ORS_API_KEY)");
            default -> code >= 500
                    ? new RoutingException(Kind.UNAVAILABLE, "openrouteservice server error " + code)
                    : new RoutingException(Kind.REJECTED, "openrouteservice rejected the request (" + code + ")");
        };
    }

    private static Stream<Throwable> causes(Throwable e) {
        return Stream.iterate(e, c -> c != null, Throwable::getCause);
    }

    private static long round(Double value) {
        return value == null ? 0 : Math.round(value);
    }

    record MatrixRequest(List<double[]> locations, List<Integer> sources, List<Integer> destinations,
            List<String> metrics, String units) {}

    record DirectionsRequest(List<double[]> coordinates, boolean instructions) {}

    record IsochronesRequest(List<double[]> locations, List<Integer> range,
            @JsonProperty("range_type") String rangeType) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MatrixResponse(List<List<Double>> durations, List<List<Double>> distances, List<Snapped> sources,
            List<Snapped> destinations) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Snapped(@JsonProperty("snapped_distance") Double snappedDistance) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FeatureCollection(List<Feature> features) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Feature(Properties properties, JsonNode geometry) {}

    /** {@code summary} on a route, {@code value} (seconds) on an isochrone. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Properties(Summary summary, Double value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Summary(Double distance, Double duration) {}
}
