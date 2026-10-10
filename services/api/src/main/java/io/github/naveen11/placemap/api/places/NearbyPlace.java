package io.github.naveen11.placemap.api.places;

/** One result row of a nearby search; {@code distanceMeters} is the straight-line (geodesic) distance. */
public record NearbyPlace(
        long id, String name, String category, String address, double lat, double lon, long distanceMeters) {}
