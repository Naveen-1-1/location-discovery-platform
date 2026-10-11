package io.github.naveen11.placemap.api.routing;

/** A WGS 84 position. Routing engines usually want it as {@code [lon, lat]}; see {@link #lonLat()}. */
public record GeoPoint(double lat, double lon) {

    public double[] lonLat() {
        return new double[] {lon, lat};
    }
}
