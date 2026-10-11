package io.github.naveen11.placemap.ingest.source;

import java.util.HashMap;
import java.util.Map;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * Converts projected coordinates to WGS84 longitude/latitude (plan: "Otherwise use proj4j, from EPSG:2249 (Boston
 * licensing) or 26986 (MassGIS)"). Sources are asked for WGS84 where the publisher can serve it; this covers the
 * ones that cannot.
 *
 * <p>proj4j transforms keep mutable state, so they must not be shared between threads: each parse worker has its own
 * Reprojector (through its {@link ParseContext}), which caches one transform per source CRS.
 */
public final class Reprojector {

    private static final CRSFactory CRS = new CRSFactory();
    private static final CoordinateTransformFactory TRANSFORMS = new CoordinateTransformFactory();

    private final Map<String, CoordinateTransform> byCrs = new HashMap<>();
    private final ProjCoordinate in = new ProjCoordinate();
    private final ProjCoordinate out = new ProjCoordinate();

    /** {lon, lat} in WGS84 for (x, y) in {@code crs}, e.g. "EPSG:2249" (US survey feet, x east, y north). */
    public double[] toWgs84(String crs, double x, double y) {
        if ("EPSG:4326".equals(crs)) {
            return new double[] {x, y};
        }
        CoordinateTransform transform = byCrs.computeIfAbsent(crs,
                name -> TRANSFORMS.createTransform(CRS.createFromName(name), CRS.createFromName("EPSG:4326")));
        in.setValue(x, y);
        transform.transform(in, out);
        return new double[] {out.x, out.y};
    }
}
