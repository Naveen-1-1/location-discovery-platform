package io.github.naveen11.placemap.api.places;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One place, for its panel on the map: what it is, what the filters know about it, and which sources
 * describe it, so the map can credit them.
 *
 * @param liveKey the live-hub key for the place's live data, or null
 * @param sources ids from data/sources.yaml of the live records behind the place, sorted
 */
public record PlaceDetails(long id, String name, String category, @Nullable String address, @Nullable String phone,
        @Nullable String website, double lat, double lon, @Nullable String liveKey, Facts facts, List<String> sources) {

    /** @param inspection null if the place was never inspected; hours null if unknown */
    public record Facts(boolean acceptsSnap, boolean licensed, @Nullable Inspection inspection, @Nullable Hours hours,
            List<Feature> parkFeatures) {
    }

    /** @param result {@code passed}, {@code failed} or {@code other} */
    public record Inspection(@Schema(allowableValues = {"passed", "failed", "other"}) String result,
            @Nullable OffsetDateTime at, @Nullable Integer failedChecks12m) {
    }

    /**
     * @param text the regular week, "Mon–Fri 07:00–23:00; Sat–Sun closed"
     * @param source {@code osm} (published hours), {@code cambridge_permit} or {@code boston_licence} (the latest the
     *     licence allows, which the map labels as such)
     * @param openNow by the regular week, at the time of this answer
     */
    public record Hours(String text, @Schema(allowableValues = {"osm", "cambridge_permit", "boston_licence"}) String source,
            boolean openNow) {
    }

    public record Feature(String name, int count) {
    }
}
