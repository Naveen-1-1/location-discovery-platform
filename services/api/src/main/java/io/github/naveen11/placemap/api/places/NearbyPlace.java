package io.github.naveen11.placemap.api.places;

import io.github.naveen11.placemap.api.routing.RoutingClient.Leg;
import tools.jackson.databind.JsonNode;

/**
 * One result row of a nearby search. {@code distanceMeters} is the straight-line (geodesic) distance from the search's
 * snapped origin; {@code travelSeconds} and {@code travelMeters} come from the routing engine and are null when there
 * is no travel time (straight-line search, fallback, or a place the engine cannot reach).
 *
 * <p>{@code liveKey} is set for places live-hub has data for, such as Bluebikes docks ({@code bikes:<station_id>}),
 * and is the key the map's live stream uses too. {@code live} is that data as live-hub sent it; it is
 * added to each answer, never kept in the shared result, and null when live-hub has nothing or did not answer.
 *
 * <p>{@code facts} carries the filters' badges; its {@code openNow} is likewise worked out per answer.
 */
public record NearbyPlace(
        long id,
        String name,
        String category,
        String address,
        double lat,
        double lon,
        long distanceMeters,
        Long travelSeconds,
        Long travelMeters,
        String liveKey,
        JsonNode live,
        PlaceFacts facts) {

    /** A place without live data or facts. */
    public NearbyPlace(long id, String name, String category, String address, double lat, double lon,
            long distanceMeters, Long travelSeconds, Long travelMeters) {
        this(id, name, category, address, lat, lon, distanceMeters, travelSeconds, travelMeters, null, null);
    }

    /** A place without facts. */
    public NearbyPlace(long id, String name, String category, String address, double lat, double lon,
            long distanceMeters, Long travelSeconds, Long travelMeters, String liveKey, JsonNode live) {
        this(id, name, category, address, lat, lon, distanceMeters, travelSeconds, travelMeters, liveKey, live,
                PlaceFacts.NONE);
    }

    public NearbyPlace withTravel(Leg leg) {
        return leg == null ? this : new NearbyPlace(id, name, category, address, lat, lon, distanceMeters,
                leg.seconds(), leg.meters(), liveKey, live, facts);
    }

    public NearbyPlace withLive(JsonNode data) {
        return new NearbyPlace(id, name, category, address, lat, lon, distanceMeters, travelSeconds, travelMeters,
                liveKey, data, facts);
    }

    /** With {@code facts.openNow} for this minute of the week. */
    public NearbyPlace at(int minuteOfWeek) {
        return new NearbyPlace(id, name, category, address, lat, lon, distanceMeters, travelSeconds, travelMeters,
                liveKey, live, facts.at(minuteOfWeek));
    }
}
