package io.github.naveen11.placemap.ingest.catalog;

import io.github.naveen11.placemap.ingest.source.GeoJson;
import java.nio.file.Path;
import java.util.ArrayList;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The four towns, from {@code data/area/area.geojson} (EPSG:4326, written by tools/fetch-area.sh).
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
        var polygons = new ArrayList<Geometry>();
        for (JsonNode feature : features.values()) {
            Geometry geometry = GeoJson.read(feature.required("geometry"));
            if (!(geometry instanceof Polygon || geometry instanceof MultiPolygon)) {
                throw new IllegalStateException("unexpected geometry in " + geojson + ": "
                        + geometry.getGeometryType());
            }
            polygons.add(geometry);
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
}
