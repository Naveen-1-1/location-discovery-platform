package io.github.naveen11.placemap.api.places;

import io.github.naveen11.placemap.api.routing.RoutingClient.Leg;

/**
 * One result row of a nearby search. {@code distanceMeters} is the straight-line (geodesic) distance from the search's
 * snapped origin; {@code travelSeconds} and {@code travelMeters} come from the routing engine and are null when there
 * is no travel time (straight-line search, fallback, or a place the engine cannot reach).
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
        Long travelMeters) {

    public NearbyPlace withTravel(Leg leg) {
        return leg == null ? this
                : new NearbyPlace(id, name, category, address, lat, lon, distanceMeters, leg.seconds(), leg.meters());
    }
}
