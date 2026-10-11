package io.github.naveen11.placemap.ingest.source;

import io.github.naveen11.placemap.ingest.catalog.CategoryRules;
import io.github.naveen11.placemap.ingest.catalog.CoverageArea;
import java.util.Optional;

/**
 * Per-worker helpers for {@link SourceAdapter#parse}. Each parse worker gets its own instance, so nothing in here has
 * to be thread-safe.
 */
public final class ParseContext {

    private final CategoryRules categories;
    private final CoverageArea.Checker area;

    public ParseContext(CategoryRules categories, CoverageArea.Checker area) {
        this.categories = categories;
        this.area = area;
    }

    public CategoryRules categories() {
        return categories;
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
        return Optional.of("outside the coverage area");
    }
}
