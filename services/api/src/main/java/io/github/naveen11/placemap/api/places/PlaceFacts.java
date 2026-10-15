package io.github.naveen11.placemap.api.places;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

/**
 * What the filters know about a place, shown as badges.
 *
 * @param inspection the latest inspection: {@code passed}, {@code failed} or {@code other}; null if never inspected
 * @param openNow whether the regular week says open at the time of this answer; null when the hours are unknown.
 *     Worked out for every answer, never kept in a shared result, like live data.
 * @param week the regular week, for working out {@code openNow}; not sent
 */
public record PlaceFacts(boolean acceptsSnap, boolean licensed,
        @Nullable @Schema(allowableValues = {"passed", "failed", "other"}) String inspection, @Nullable Boolean openNow,
        @JsonIgnore OpenWeek week) {

    public static final PlaceFacts NONE = new PlaceFacts(false, false, null, null, null);

    public PlaceFacts at(int minuteOfWeek) {
        return new PlaceFacts(acceptsSnap, licensed, inspection, week == null ? null : week.openAt(minuteOfWeek), week);
    }
}
