package io.github.naveen11.placemap.api.routing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.uber.h3core.H3Core;
import com.uber.h3core.LengthUnit;
import com.uber.h3core.util.LatLng;
import io.github.naveen11.placemap.api.routing.RoutingException.Kind;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link RoutingClient} for a self-hosted Valhalla: its {@code sources_to_targets}, {@code route} and
 * {@code isochrone} actions, with Massachusetts tiles.
 *
 * <p>Valhalla moves a location to a path up to 35 km away by default: a point out at sea was moved 955 m to an island
 * and given a 46-minute walk by ferry. The same rule as for openrouteservice applies: a target moved further than
 * {@code maxSnapMeters} counts as unreachable (the matrix lists where it moved each target), and an origin, a route's
 * ends or an isochrone's centre may only be moved that far, so the engine itself refuses anything further.
 *
 * <p>The matrix search stops at a road distance of {@value #DETOUR} times the farthest target's straight-line distance
 * (at least {@value #MIN_EXPANSION_METERS} m), and a target further than that by road is unreachable. Without the
 * limit a car matrix searched far past its targets: 1 to 3 s for 200 places within 1.4 km of Boston Common, against
 * 60 to 80 ms with it.
 *
 * <p>Error messages never include Valhalla's own text, which can echo the request.
 */
public final class ValhallaRoutingClient implements RoutingClient {

    /** Valhalla's default {@code max_matrix_location_pairs} for every costing we use. */
    static final int MAX_TARGETS = 2_500;

    /**
     * Answers that mean "no route": 170 locations in unconnected regions, 171 no suitable edges near a location,
     * 441 location unreachable, 442 no path found, 443 the exact route algorithm found none.
     */
    static final Set<Integer> UNROUTABLE_CODES = Set.of(170, 171, 441, 442, 443);

    /** How much longer than the straight line a route to a target may be before the matrix stops looking. */
    static final int DETOUR = 3;

    static final int MIN_EXPANSION_METERS = 2_000;

    private static final Pattern ERROR_CODE = Pattern.compile("\"error_code\"\\s*:\\s*(\\d+)");
    private static final int MAX_ERROR_BODY = 4_096;

    private final RestClient matrix;
    private final RestClient slow;
    private final double maxSnapMeters;
    private final H3Core h3;

    /**
     * @param matrix the client for travel times, with a timeout that fits nearby's deadline
     * @param slow the client for routes and isochrones
     * @param h3 for great-circle distances
     */
    public ValhallaRoutingClient(RestClient matrix, RestClient slow, double maxSnapMeters, H3Core h3) {
        this.matrix = matrix;
        this.slow = slow;
        this.maxSnapMeters = maxSnapMeters;
        this.h3 = h3;
    }

    /** The base URL and accepted type both clients share. */
    public static RestClient.Builder configure(RestClient.Builder builder, RoutingProperties.Valhalla valhalla) {
        return builder.baseUrl(valhalla.baseUrl().toString())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
    }

    @Override
    public String name() {
        return "valhalla";
    }

    @Override
    public List<Leg> travelTimes(GeoPoint origin, List<GeoPoint> targets, TravelMode mode) {
        if (targets.isEmpty()) {
            return List.of();
        }
        if (targets.size() > MAX_TARGETS) {
            throw new IllegalArgumentException("at most " + MAX_TARGETS + " targets per call, got " + targets.size());
        }
        // Targets keep Valhalla's own search distance: one cut off fails the whole call (error 170), so the distance
        // each was moved is checked here instead.
        double farthest = targets.stream().mapToDouble(t -> distanceMeters(origin, t.lat(), t.lon())).max().orElse(0);
        var request = new MatrixRequest(List.of(Location.near(origin, maxSnapMeters)),
                targets.stream().map(Location::of).toList(), costing(mode), "kilometers", true,
                Math.max(MIN_EXPANSION_METERS, (int) Math.ceil(DETOUR * farthest)));
        MatrixResponse response = post(matrix, "/sources_to_targets", request, MatrixResponse.class);

        List<Position> snapped = response.targets();
        if (response.sourcesToTargets() == null || response.sourcesToTargets().size() != 1
                || response.sourcesToTargets().getFirst() == null
                || response.sourcesToTargets().getFirst().size() != targets.size()
                || snapped == null || snapped.size() != targets.size()) {
            throw new RoutingException(Kind.UNAVAILABLE, "Valhalla returned an unexpected matrix");
        }
        var legs = new ArrayList<Leg>(Collections.nCopies(targets.size(), null));
        for (Cell cell : response.sourcesToTargets().getFirst()) {
            if (cell == null || cell.toIndex() == null || cell.toIndex() < 0 || cell.toIndex() >= targets.size()) {
                throw new RoutingException(Kind.UNAVAILABLE, "Valhalla returned an unexpected matrix");
            }
            int i = cell.toIndex();
            boolean reachable = cell.time() != null && cell.distance() != null
                    && closeEnough(targets.get(i), snapped.get(i));
            legs.set(i, reachable ? new Leg(Math.round(cell.time()), Math.round(cell.distance() * 1_000)) : null);
        }
        return Collections.unmodifiableList(legs);
    }

    @Override
    public Route route(GeoPoint from, GeoPoint to, TravelMode mode) {
        var request = new RouteRequest(List.of(Location.near(from, maxSnapMeters), Location.near(to, maxSnapMeters)),
                costing(mode), "kilometers", "none");
        RouteResponse response = post(slow, "/route", request, RouteResponse.class);
        Trip trip = response.trip();
        if (trip == null || trip.summary() == null || trip.legs() == null || trip.legs().isEmpty()
                || trip.legs().stream().anyMatch(leg -> leg == null || leg.shape() == null)) {
            throw new RoutingException(Kind.UNAVAILABLE, "Valhalla returned no route");
        }
        ObjectNode line = JsonNodeFactory.instance.objectNode().put("type", "LineString");
        ArrayNode coordinates = line.putArray("coordinates");
        for (TripLeg leg : trip.legs()) {
            decodePolyline6(leg.shape(), coordinates);
        }
        Summary summary = trip.summary();
        return new Route(round(summary.time()), summary.length() == null ? 0 : Math.round(summary.length() * 1_000),
                line);
    }

    @Override
    public List<Isochrone> isochrones(GeoPoint origin, TravelMode mode, List<Integer> minutes) {
        if (minutes.isEmpty()) {
            return List.of();
        }
        List<Integer> limits = minutes.stream().distinct().sorted().toList();
        var request = new IsochroneRequest(List.of(Location.near(origin, maxSnapMeters)), costing(mode),
                limits.stream().map(Contour::new).toList(), true);
        FeatureCollection response = post(slow, "/isochrone", request, FeatureCollection.class);
        List<Feature> areas = response.features() == null ? List.of() : response.features().stream()
                .filter(f -> f.properties() != null && f.properties().contour() != null && f.geometry() != null
                        && f.geometry().has("type"))
                .toList();
        if (areas.size() != limits.size()) {
            throw new RoutingException(Kind.UNAVAILABLE, "Valhalla returned unexpected isochrones");
        }
        return areas.stream()
                .sorted(Comparator.comparingDouble(f -> f.properties().contour()))
                .map(f -> new Isochrone((int) Math.round(f.properties().contour()), f.geometry()))
                .toList();
    }

    static String costing(TravelMode mode) {
        return switch (mode) {
            case WALK -> "pedestrian";
            case BIKE -> "bicycle";
            case DRIVE -> "auto";
        };
    }

    private static <T> T post(RestClient client, String path, Object body, Class<T> type) {
        T result;
        try {
            result = client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw failure(response.getStatusCode(), errorCode(response.getBody()));
                    })
                    .body(type);
        } catch (ResourceAccessException e) {
            boolean timedOut = causes(e).anyMatch(c -> c instanceof HttpTimeoutException
                    || c instanceof SocketTimeoutException);
            throw timedOut
                    ? new RoutingException(Kind.TIMEOUT, "Valhalla did not answer in time", e)
                    : new RoutingException(Kind.UNAVAILABLE, "Valhalla is unreachable", e);
        } catch (RestClientException e) {
            throw new RoutingException(Kind.UNAVAILABLE, "unreadable answer from Valhalla", e);
        }
        if (result == null) {
            throw new RoutingException(Kind.UNAVAILABLE, "empty answer from Valhalla");
        }
        return result;
    }

    /** Valhalla's {@code error_code} from an error body, or -1. */
    static int errorCode(InputStream body) {
        try (body) {
            String text = new String(body.readNBytes(MAX_ERROR_BODY), StandardCharsets.UTF_8);
            Matcher m = ERROR_CODE.matcher(text);
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    static RoutingException failure(HttpStatusCode status, int errorCode) {
        int code = status.value();
        if (UNROUTABLE_CODES.contains(errorCode)) {
            return new RoutingException(Kind.UNROUTABLE, "Valhalla found no route (" + errorCode + ")");
        }
        if (code == 429) {
            return new RoutingException(Kind.RATE_LIMITED, "Valhalla is rate limited");
        }
        return code >= 500
                ? new RoutingException(Kind.UNAVAILABLE, "Valhalla server error " + code)
                : new RoutingException(Kind.REJECTED, "Valhalla rejected the request (" + code
                        + (errorCode < 0 ? "" : ", error " + errorCode) + ")");
    }

    /** Appends a polyline with six decimal places (Valhalla's shape encoding) as [lon, lat] positions. */
    static void decodePolyline6(String encoded, ArrayNode coordinates) {
        int index = 0;
        long lat = 0;
        long lon = 0;
        while (index < encoded.length()) {
            long[] next = new long[2];
            for (int axis = 0; axis < 2; axis++) {
                long result = 0;
                int shift = 0;
                int b;
                do {
                    if (index >= encoded.length()) {
                        throw new RoutingException(Kind.UNAVAILABLE, "Valhalla returned a broken route shape");
                    }
                    b = encoded.charAt(index++) - 63;
                    result |= (long) (b & 0x1f) << shift;
                    shift += 5;
                } while (b >= 0x20);
                next[axis] = (result & 1) != 0 ? ~(result >> 1) : result >> 1;
            }
            lat += next[0];
            lon += next[1];
            coordinates.addArray().add(lon / 1e6).add(lat / 1e6);
        }
    }

    private boolean closeEnough(GeoPoint target, Position snapped) {
        return snapped != null && snapped.lat() != null && snapped.lon() != null
                && distanceMeters(target, snapped.lat(), snapped.lon()) <= maxSnapMeters;
    }

    private double distanceMeters(GeoPoint p, double lat, double lon) {
        return h3.greatCircleDistance(new LatLng(p.lat(), p.lon()), new LatLng(lat, lon), LengthUnit.m);
    }

    private static Stream<Throwable> causes(Throwable e) {
        return Stream.iterate(e, c -> c != null, Throwable::getCause);
    }

    private static long round(Double value) {
        return value == null ? 0 : Math.round(value);
    }

    /** @param searchCutoff how far Valhalla may move it to reach a path, in metres; null keeps the engine's 35 km */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Location(double lat, double lon, @JsonProperty("search_cutoff") Double searchCutoff) {
        static Location of(GeoPoint p) {
            return new Location(p.lat(), p.lon(), null);
        }

        static Location near(GeoPoint p, double cutoffMeters) {
            return new Location(p.lat(), p.lon(), cutoffMeters);
        }
    }

    record MatrixRequest(List<Location> sources, List<Location> targets, String costing, String units,
            boolean verbose, @JsonProperty("expansion_max_distance") int expansionMaxDistance) {}

    record RouteRequest(List<Location> locations, String costing, String units,
            @JsonProperty("directions_type") String directionsType) {}

    record Contour(int time) {}

    record IsochroneRequest(List<Location> locations, String costing, List<Contour> contours, boolean polygons) {}

    /** @param targets where Valhalla moved each target to reach a path, in target order */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MatrixResponse(@JsonProperty("sources_to_targets") List<List<Cell>> sourcesToTargets,
            List<Position> targets) {}

    /** One origin-target pair: time in seconds and distance in kilometres, both null when unreachable. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Cell(Double time, Double distance, @JsonProperty("to_index") Integer toIndex) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Position(Double lat, Double lon) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RouteResponse(Trip trip) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Trip(Summary summary, List<TripLeg> legs) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TripLeg(String shape) {}

    /** @param length kilometres */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Summary(Double time, Double length) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FeatureCollection(List<Feature> features) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Feature(Properties properties, JsonNode geometry) {}

    /** @param contour the limit in minutes */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Properties(Double contour) {}
}
