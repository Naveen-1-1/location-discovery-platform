package io.github.naveen11.placemap.ingest.source;

import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.WKTWriter;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;
import tools.jackson.databind.JsonNode;

/** Reads the GeoJSON geometries our sources and our area file hold (points and polygons, EPSG:4326) into JTS. */
public final class GeoJson {

    static final GeometryFactory GEOMETRY = new GeometryFactory();

    /**
     * Outlines are simplified to about 2 m before they are stored: enough to tell whether a point lies in a park, at a
     * fraction of the surveyed vertex count.
     */
    static final double OUTLINE_TOLERANCE_DEGREES = 0.00002;

    private GeoJson() {
    }

    public static Geometry read(JsonNode geometry) {
        JsonNode coordinates = geometry.required("coordinates");
        return switch (geometry.required("type").asString()) {
            case "Point" -> GEOMETRY.createPoint(position(coordinates));
            case "Polygon" -> polygon(coordinates);
            case "MultiPolygon" -> GEOMETRY.createMultiPolygon(
                    coordinates.values().stream().map(GeoJson::polygon).toArray(Polygon[]::new));
            default -> throw new IllegalArgumentException("unexpected geometry " + geometry.get("type"));
        };
    }

    /** The point itself, or a point guaranteed to lie inside the outline (a centroid can fall outside an L-shape). */
    static Coordinate representativePoint(Geometry geometry) {
        return geometry.getInteriorPoint().getCoordinate();
    }

    /** A simplified outline as MULTIPOLYGON WKT, or null for a point or an outline that simplifies to nothing. */
    static String outlineWkt(Geometry geometry) {
        if (!(geometry instanceof Polygon || geometry instanceof MultiPolygon)) {
            return null;
        }
        Geometry simple = TopologyPreservingSimplifier.simplify(geometry, OUTLINE_TOLERANCE_DEGREES);
        if (simple.isEmpty()) {
            return null;
        }
        MultiPolygon multi = simple instanceof Polygon p ? GEOMETRY.createMultiPolygon(new Polygon[] {p}) : (MultiPolygon) simple;
        var writer = new WKTWriter();
        writer.setPrecisionModel(new PrecisionModel(1e7)); // 7 decimals: about 1 cm
        return writer.write(multi);
    }

    private static Coordinate position(JsonNode p) {
        return new Coordinate(p.get(0).asDouble(), p.get(1).asDouble());
    }

    private static Polygon polygon(JsonNode rings) {
        List<LinearRing> parsed = rings.values().stream()
                .map(ring -> GEOMETRY.createLinearRing(ring.values().stream().map(GeoJson::position).toArray(Coordinate[]::new)))
                .toList();
        return GEOMETRY.createPolygon(parsed.getFirst(), parsed.subList(1, parsed.size()).toArray(LinearRing[]::new));
    }
}
