package io.github.naveen11.placemap.api.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naveen11.placemap.api.routing.RoutingClient.Isochrone;
import io.github.naveen11.placemap.api.routing.RoutingClient.Leg;
import io.github.naveen11.placemap.api.routing.RoutingClient.Route;
import io.github.naveen11.placemap.api.routing.RoutingException.Kind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * What every routing adapter must do (ADR 0033): openrouteservice now, Valhalla at deployment. The cases check what a
 * correct engine does on real Boston streets, not exact numbers, so the same cases hold for both engines.
 */
abstract class RoutingClientContract {

    static final GeoPoint BOSTON_COMMON = new GeoPoint(42.3550, -71.0656);
    static final GeoPoint FROG_POND = new GeoPoint(42.3561, -71.0656);
    static final GeoPoint HARVARD_SQUARE = new GeoPoint(42.3736, -71.1190);
    static final GeoPoint OUT_AT_SEA = new GeoPoint(42.30, -70.80);

    abstract RoutingClient client();

    @Test
    void walkingTakesLongerTheFurtherAndNoOneWalksOnWater() {
        List<Leg> legs = client().travelTimes(BOSTON_COMMON, List.of(FROG_POND, HARVARD_SQUARE, OUT_AT_SEA),
                TravelMode.WALK);

        assertThat(legs).hasSize(3);
        assertThat(legs.get(0).seconds()).isBetween(30L, 10 * 60L);           // about 120 m away
        assertThat(legs.get(1).seconds()).isBetween(40 * 60L, 100 * 60L);     // about 5 km by foot
        assertThat(legs.get(1).meters()).isBetween(4_000L, 8_000L);
        assertThat(legs.get(2)).isNull();
    }

    @Test
    void cyclingBeatsWalking() {
        Leg walk = client().travelTimes(BOSTON_COMMON, List.of(HARVARD_SQUARE), TravelMode.WALK).getFirst();
        Leg bike = client().travelTimes(BOSTON_COMMON, List.of(HARVARD_SQUARE), TravelMode.BIKE).getFirst();

        assertThat(bike.seconds()).isLessThan(walk.seconds() / 2);
    }

    @Test
    void aRouteIsALineFromStartToEnd() {
        Route route = client().route(BOSTON_COMMON, HARVARD_SQUARE, TravelMode.WALK);

        assertThat(route.seconds()).isBetween(40 * 60L, 100 * 60L);
        assertThat(route.geometry().get("type").asString()).isEqualTo("LineString");
        JsonNode line = route.geometry().get("coordinates");
        assertThat(line.size()).isGreaterThan(10);
        assertThat(distanceMeters(line.get(0), BOSTON_COMMON)).isLessThan(350);
        assertThat(distanceMeters(line.get(line.size() - 1), HARVARD_SQUARE)).isLessThan(350);
    }

    @Test
    void aLongerLimitReachesFurther() {
        List<Isochrone> areas = client().isochrones(BOSTON_COMMON, TravelMode.WALK, List.of(5, 10));

        assertThat(areas).extracting(Isochrone::minutes).containsExactly(5, 10);
        double[] small = bbox(areas.get(0).geometry());
        double[] large = bbox(areas.get(1).geometry());
        assertThat(large[0]).isLessThanOrEqualTo(small[0]);
        assertThat(large[1]).isLessThanOrEqualTo(small[1]);
        assertThat(large[2]).isGreaterThanOrEqualTo(small[2]);
        assertThat(large[3]).isGreaterThanOrEqualTo(small[3]);
        assertThat(BOSTON_COMMON.lon()).isBetween(small[0], small[2]);
        assertThat(BOSTON_COMMON.lat()).isBetween(small[1], small[3]);
    }

    @Test
    void thereIsNoRouteFromTheSea() {
        assertThatThrownBy(() -> client().route(OUT_AT_SEA, BOSTON_COMMON, TravelMode.WALK))
                .isInstanceOf(RoutingException.class)
                .extracting(e -> ((RoutingException) e).kind()).isEqualTo(Kind.UNROUTABLE);
    }

    private static double distanceMeters(JsonNode lonLat, GeoPoint p) {
        double dLat = Math.toRadians(lonLat.get(1).asDouble() - p.lat());
        double dLon = Math.toRadians(lonLat.get(0).asDouble() - p.lon()) * Math.cos(Math.toRadians(p.lat()));
        return 6_371_000 * Math.hypot(dLat, dLon);
    }

    /** [minLon, minLat, maxLon, maxLat] over every position of a Polygon or MultiPolygon. */
    private static double[] bbox(JsonNode geometry) {
        var positions = new ArrayList<JsonNode>();
        collect(geometry.get("coordinates"), positions);
        double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (JsonNode p : positions) {
            box[0] = Math.min(box[0], p.get(0).asDouble());
            box[1] = Math.min(box[1], p.get(1).asDouble());
            box[2] = Math.max(box[2], p.get(0).asDouble());
            box[3] = Math.max(box[3], p.get(1).asDouble());
        }
        return box;
    }

    private static void collect(JsonNode node, List<JsonNode> positions) {
        if (node.isArray() && !node.isEmpty() && node.get(0).isNumber()) {
            positions.add(node);
        } else {
            node.forEach(child -> collect(child, positions));
        }
    }
}
