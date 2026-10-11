package io.github.naveen11.placemap.api.places;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** The regular week as stored, the minute of the week in Boston, and park features as stored. */
class OpenWeekTest {

    @Test
    void readsTheStoredMultirange() {
        OpenWeek week = OpenWeek.parse("{[480,1080),[9900,10080)}");

        assertThat(week.openAt(480)).isTrue();
        assertThat(week.openAt(1079)).isTrue();
        assertThat(week.openAt(1080)).as("ranges end before their upper bound").isFalse();
        assertThat(week.openAt(10079)).isTrue();
        assertThat(OpenWeek.parse("{}").openAt(0)).as("closed all week").isFalse();
        assertThat(OpenWeek.parse(null)).as("unknown").isNull();
    }

    @Test
    void theMinuteOfTheWeekIsBostonTimeWhateverTheServerZone() {
        // Sunday 23:30 in Boston is already Monday in UTC.
        Clock sundayNight = Clock.fixed(Instant.parse("2026-10-12T03:30:00Z"), ZoneOffset.UTC);
        assertThat(BostonTime.minuteOfWeek(sundayNight)).isEqualTo(6 * 1440 + 23 * 60 + 30);
        // In winter Boston is UTC-5: Monday 09:00 is 14:00 UTC.
        assertThat(BostonTime.minuteOfWeek(Clock.fixed(Instant.parse("2026-12-07T14:00:00Z"), ZoneOffset.UTC)))
                .isEqualTo(9 * 60);
    }

    @Test
    void parkFeaturesAreReadWithTheirCounts() {
        assertThat(PlaceRepository.features("Basketball:2;Dog Park:1"))
                .containsExactly(new PlaceDetails.Feature("Basketball", 2), new PlaceDetails.Feature("Dog Park", 1));
        assertThat(PlaceRepository.features(null)).isEmpty();
    }
}
