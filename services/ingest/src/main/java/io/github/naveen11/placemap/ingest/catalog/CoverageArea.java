package io.github.naveen11.placemap.ingest.catalog;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The four towns (ADR 0005), from {@code data/area/area.geojson} (EPSG:4326, written by tools/fetch-area.sh).
 * Point checks go through a {@link Checker}; each parse worker takes its own, so no geometry index is shared
 * between threads.
 */
public final class CoverageArea {

    private static final GeometryFactory GEOMETRY = new GeometryFactory();

    private final Geometry union;

    private CoverageArea(Geometry union) {
        this.union = union;
    }

    public static CoverageArea load(Path geojson) {
        JsonNode features = new JsonMapper().readTree(geojson).required("features");
        var polygons = new ArrayList<Polygon>();
        for (JsonNode feature : features.values()) {
            JsonNode geometry = feature.required("geometry");
            JsonNode coordinates = geometry.required("coordinates");
            switch (geometry.required("type").asString()) {
                case "Polygon" -> polygons.add(polygon(coordinates));
                case "MultiPolygon" -> coordinates.values().forEach(p -> polygons.add(polygon(p)));
                default -> throw new IllegalStateException("unexpected geometry in " + geojson + ": " + geometry.get("type"));
            }
        }
        if (polygons.isEmpty()) {
            throw new IllegalStateException("no polygons in " + geojson);
        }
        return new CoverageArea(GEOMETRY.buildGeometry(polygons).union());
    }

    public Checker checker() {
        return new Checker(PreparedGeometryFactory.prepare(union));
    }

    /** The bounding box {minLon, minLat, maxLon, maxLat}, for pushing a coarse filter down to a data source. */
    public double[] bounds() {
        var envelope = union.getEnvelopeInternal();
        return new double[] {envelope.getMinX(), envelope.getMinY(), envelope.getMaxX(), envelope.getMaxY()};
    }

    /** Fast point-in-area checks for one thread. */
    public static final class Checker {
        private final PreparedGeometry area;

        private Checker(PreparedGeometry area) {
            this.area = area;
        }

        public boolean covers(double lon, double lat) {
            return area.covers(GEOMETRY.createPoint(new Coordinate(lon, lat)));
        }
    }

    private static Polygon polygon(JsonNode rings) {
        List<LinearRing> parsed = rings.values().stream().map(CoverageArea::ring).toList();
        return GEOMETRY.createPolygon(parsed.getFirst(), parsed.subList(1, parsed.size()).toArray(LinearRing[]::new));
    }

    private static LinearRing ring(JsonNode positions) {
        Coordinate[] coords = positions.values().stream()
                .map(p -> new Coordinate(p.get(0).asDouble(), p.get(1).asDouble()))
                .toArray(Coordinate[]::new);
        return GEOMETRY.createLinearRing(coords);
    }
}
