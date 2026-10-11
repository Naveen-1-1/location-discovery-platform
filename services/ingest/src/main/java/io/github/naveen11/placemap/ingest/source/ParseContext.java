package io.github.naveen11.placemap.ingest.source;

import io.github.naveen11.placemap.ingest.catalog.CategoryRules;
import io.github.naveen11.placemap.ingest.catalog.CoverageArea;
import java.util.Optional;

/**
 * Per-worker helpers for {@link SourceAdapter#parse}. Each parse worker gets its own instance, so nothing in here has
 * to be thread-safe.
 */
public final class ParseContext {

    /** The {@link #pointProblem} reason for a valid point outside the four towns. */
    public static final String OUTSIDE_AREA = "outside the coverage area";

    private final CategoryRules categories;
    private final CoverageArea.Checker area;
    private final Reprojector reprojector = new Reprojector();

    public ParseContext(CategoryRules categories, CoverageArea.Checker area) {
        this.categories = categories;
        this.area = area;
    }

    public CategoryRules categories() {
        return categories;
    }

    /** {lon, lat} in WGS84 for projected coordinates, e.g. EPSG:2249 feet from Boston's licensing data. */
    public double[] toWgs84(String crs, double x, double y) {
        return reprojector.toWgs84(crs, x, y);
    }

    /** A point read by {@link #readPoint}: {@code failure} is null when the point is usable. */
    public record ReadPoint(double lon, double lat, RowResult failure) {
    }

    /**
     * Reads a WGS84 point from two text values. Missing or unreadable values and the defects {@link #pointProblem}
     * finds are rejections. A valid point outside the four towns is a skip: statewide and regional lists are valid,
     * just not ours.
     */
    public ReadPoint readPoint(String lonText, String latText) {
        if (lonText == null || latText == null) {
            return new ReadPoint(0, 0, RowResult.rejected("missing coordinates"));
        }
        try {
            return checkPoint(Double.parseDouble(lonText), Double.parseDouble(latText));
        } catch (NumberFormatException e) {
            return new ReadPoint(0, 0, RowResult.rejected("unreadable coordinates"));
        }
    }

    /** {@link #readPoint} for a point already in numbers, such as one just reprojected. */
    public ReadPoint checkPoint(double lon, double lat) {
        Optional<String> problem = pointProblem(lon, lat);
        if (problem.isEmpty()) {
            return new ReadPoint(lon, lat, null);
        }
        return new ReadPoint(lon, lat, OUTSIDE_AREA.equals(problem.get())
                ? RowResult.skipped(problem.get()) : RowResult.rejected(problem.get()));
    }

    /**
     * Why a WGS84 point is unusable, or empty if it is fine. Catches the usual defects in official lists: (0,0),
     * out-of-range values, latitude and longitude swapped, and points outside the four towns.
     */
    public Optional<String> pointProblem(double lon, double lat) {
        if (lon == 0 || lat == 0) {
            return Optional.of("zero coordinate");
        }
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            return Optional.of("coordinates out of range");
        }
        if (area.covers(lon, lat)) {
            return Optional.empty();
        }
        if (Math.abs(lon) <= 90 && area.covers(lat, lon)) {
            return Optional.of("latitude and longitude swapped");
        }
        return Optional.of(OUTSIDE_AREA);
    }
}
