package io.github.naveen11.placemap.livehub.feeds;

/**
 * Where vehicles are worth sending: the map shows only the four towns, so a vehicle outside their bounding box, with
 * about a kilometre's margin, is left out. A weekday poll then carries about a fifth fewer.
 */
public record Area(double minLon, double minLat, double maxLon, double maxLat) {

    /** About 1.1 km of latitude, 0.8 km of longitude at Boston. */
    static final double MARGIN_DEGREES = 0.01;

    /** No filter, for tests that are about something else. */
    public static final Area ANYWHERE = new Area(-180, -90, 180, 90);

    /** {@code "minLon,minLat,maxLon,maxLat"}, widened by the margin. */
    public static Area around(String box) {
        String[] p = box.split(",");
        if (p.length != 4) {
            throw new IllegalArgumentException("an area is minLon,minLat,maxLon,maxLat, not " + box);
        }
        return new Area(Double.parseDouble(p[0].trim()) - MARGIN_DEGREES, Double.parseDouble(p[1].trim()) - MARGIN_DEGREES,
                Double.parseDouble(p[2].trim()) + MARGIN_DEGREES, Double.parseDouble(p[3].trim()) + MARGIN_DEGREES);
    }

    public boolean contains(double lat, double lon) {
        return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
    }
}
