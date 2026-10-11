package io.github.naveen11.placemap.api.places;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** The minute of the week in Boston, the time zone every place's hours are in. */
public final class BostonTime {

    public static final ZoneId ZONE = ZoneId.of("America/New_York");

    private BostonTime() {
    }

    /** Minutes since Monday 00:00 local time: 0 to 10079. */
    public static int minuteOfWeek(Clock clock) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(ZONE));
        return (now.getDayOfWeek().getValue() - 1) * 1440 + now.getHour() * 60 + now.getMinute();
    }
}
