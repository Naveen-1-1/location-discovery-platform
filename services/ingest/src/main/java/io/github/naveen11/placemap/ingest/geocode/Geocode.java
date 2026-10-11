package io.github.naveen11.placemap.ingest.geocode;

/**
 * What geocoding decided for one address. It is cached by its query, so every later run, and a run resumed after a
 * crash, places the record exactly where the first one did.
 *
 * @param quality {@link #HOUSE} when a candidate passed the acceptance rule, else {@link #NONE}
 * @param detail the accepted candidate's label, or why none was accepted
 */
public record Geocode(Double lon, Double lat, String quality, String detail) {

    public static final String HOUSE = "house";
    public static final String NONE = "none";

    public static Geocode house(double lon, double lat, String label) {
        return new Geocode(lon, lat, HOUSE, label);
    }

    public static Geocode none(String reason) {
        return new Geocode(null, null, NONE, reason);
    }

    public boolean located() {
        return lon != null;
    }
}
