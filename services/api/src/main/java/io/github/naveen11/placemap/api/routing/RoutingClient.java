package io.github.naveen11.placemap.api.routing;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Travel times, routes and isochrones from a routing engine: openrouteservice during development, self-hosted
 * Valhalla at deployment (ADR 0006, 0033). Every method throws {@link RoutingException} when the engine gives no
 * answer, so callers can fall back (ADR 0026).
 */
public interface RoutingClient {

    /** The engine's name, reported to clients as {@code travelTimeSource}. */
    String name();

    /**
     * Travel from one origin to each target, in target order. An entry is null when the engine cannot reach that
     * target, for example one far from any path or road.
     */
    List<Leg> travelTimes(GeoPoint origin, List<GeoPoint> targets, TravelMode mode);

    Route route(GeoPoint from, GeoPoint to, TravelMode mode);

    /** One area per limit, smallest first: everywhere reachable from the origin within that many minutes. */
    List<Isochrone> isochrones(GeoPoint origin, TravelMode mode, List<Integer> minutes);

    record Leg(long seconds, long meters) {}

    /** @param geometry a GeoJSON LineString */
    record Route(long seconds, long meters, JsonNode geometry) {}

    /** @param geometry a GeoJSON Polygon or MultiPolygon */
    record Isochrone(int minutes, JsonNode geometry) {}

    /** A client for when no engine is configured: every call fails as UNAVAILABLE, so callers fall back. */
    static RoutingClient unavailable(String reason) {
        return new RoutingClient() {
            @Override
            public String name() {
                return "none";
            }

            @Override
            public List<Leg> travelTimes(GeoPoint origin, List<GeoPoint> targets, TravelMode mode) {
                throw new RoutingException(RoutingException.Kind.UNAVAILABLE, reason);
            }

            @Override
            public Route route(GeoPoint from, GeoPoint to, TravelMode mode) {
                throw new RoutingException(RoutingException.Kind.UNAVAILABLE, reason);
            }

            @Override
            public List<Isochrone> isochrones(GeoPoint origin, TravelMode mode, List<Integer> minutes) {
                throw new RoutingException(RoutingException.Kind.UNAVAILABLE, reason);
            }
        };
    }
}
