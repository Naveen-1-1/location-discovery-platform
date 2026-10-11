package io.github.naveen11.placemap.api.places;

import io.github.naveen11.placemap.api.routing.GeoPoint;

/**
 * The centre of the H3 cell a search started in (ADR 0025).
 *
 * @param cell the H3 index in hexadecimal
 * @param maxErrorMeters how far the real starting point can be from the centre: the distance to the cell's farthest
 *     corner, rounded up
 */
public record SnappedOrigin(String cell, double lat, double lon, int maxErrorMeters) {

    public GeoPoint point() {
        return new GeoPoint(lat, lon);
    }
}
