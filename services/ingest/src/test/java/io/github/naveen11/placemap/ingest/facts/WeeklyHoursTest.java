package io.github.naveen11.placemap.ingest.facts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

/** Opening hours as OSM, Cambridge's permits and Boston's licences write them, into one regular week. */
class WeeklyHoursTest {

    private static String week(Optional<WeeklyHours> hours) {
        return hours.map(WeeklyHours::describe).orElse("unknown");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            Mo-Fr 08:00-18:00; Sa 09:00-14:00              | Mon–Fri 08:00–18:00; Sat 09:00–14:00; Sun closed
            24/7                                           | Mon–Sun 00:00–24:00
            Mo-Su 11:00-23:00                              | Mon–Sun 11:00–23:00
            Tu-Su 17:00-02:00                              | Mon closed; Tue–Sun 17:00–02:00
            Mo-Fr 07:00-12:00,13:00-17:00                  | Mon–Fri 07:00–12:00, 13:00–17:00; Sat–Sun closed
            Mo-Fr 09:00-17:00; PH off                      | Mon–Fri 09:00–17:00; Sat–Sun closed
            Mo-Sa 10:00-21:00; Su 11:00-19:00; Dec 25 off  | Mon–Sat 10:00–21:00; Sun 11:00–19:00
            Mo-Su 08:00-22:00; We off                      | Mon–Tue 08:00–22:00; Wed closed; Thu–Sun 08:00–22:00
            Fr-Mo 10:00-16:00                              | Mon 10:00–16:00; Tue–Thu closed; Fri–Sun 10:00–16:00
            Mo-Fr 08:00-17:00, Sa 10:00-14:00              | Mon–Fri 08:00–17:00; Sat 10:00–14:00; Sun closed
            10:00-22:00                                    | Mon–Sun 10:00–22:00
            sunrise-sunset                                 | unknown
            Jun-Aug Mo-Su 10:00-20:00                      | unknown
            not opening hours at all                       | unknown
            """)
    void osmHoursBecomeARegularWeek(String osm, String expected) {
        assertThat(week(WeeklyHours.fromOsm(osm))).isEqualTo(expected);
    }

    @Test
    void theWeekIsMinutesSinceMondayAndWrapsOverSundayMidnight() {
        assertThat(WeeklyHours.fromOsm("Mo 08:00-09:00; Su 23:00-01:00").orElseThrow().multirange())
                .isEqualTo("{[0,60),[480,540),[10020,10080)}");
        assertThat(WeeklyHours.fromOsm("24/7").orElseThrow().multirange()).isEqualTo("{[0,10080)}");
        assertThat(WeeklyHours.fromOsm("off").orElseThrow().multirange()).as("closed all week").isEqualTo("{}");
    }

    @Test
    void bostonLicencesHaveOneOpeningAndOneClosingTimeForEveryDay() {
        assertThat(week(WeeklyHours.daily("10:00 AM", "1:00 AM"))).isEqualTo("Mon–Sun 10:00–01:00");
        assertThat(week(WeeklyHours.daily("7:00 AM", "MIDNIGHT"))).isEqualTo("Mon–Sun 07:00–24:00");
        assertThat(week(WeeklyHours.daily("11:30 AM", "11:00 PM"))).isEqualTo("Mon–Sun 11:30–23:00");
        assertThat(week(WeeklyHours.daily(null, "2:00 AM"))).as("only a closing time").isEqualTo("unknown");
    }

    @Test
    void cambridgePermitHoursAreReadAsPeopleWriteThem() {
        assertThat(week(perDay("4:00 PM - 10:00 PM", "3pm-10pm", "4-9:00 PM", "7:00 p.m. - 10:00 a.m",
                "9:00 am-11:00 pm", "12:00 PM -9:00 PM", "Closed")))
                .isEqualTo("Mon 16:00–22:00; Tue 15:00–22:00; Wed 16:00–21:00; Thu 19:00–10:00; Fri 09:00–23:00; "
                        + "Sat 12:00–21:00; Sun closed");
        assertThat(week(perDay("11-2pm", "11-2pm", "11-2pm", "11-2pm", "11-2pm", "11-2pm", "11-2pm")))
                .as("a start without am/pm that would end before it began is morning").isEqualTo("Mon–Sun 11:00–14:00");
        assertThat(week(perDay("by appointment", "9-5", "9-5", "9-5", "9-5", "9-5", "9-5"))).isEqualTo("unknown");
    }

    @Test
    void publishedHoursWinOverPermitsAndPermitsOverLicensedHours() {
        var json = new JsonMapper();
        var osm = json.readTree("{\"opening_hours\": \"Mo-Fr 08:00-18:00\"}");
        var licence = json.readTree("{\"opening\": \"6:00 AM\", \"closing\": \"2:00 AM\"}");
        var unreadable = json.readTree("{\"opening_hours\": \"call us\"}");

        assertThat(PlaceFacts.best(Map.of("osm_geofabrik_massachusetts", osm, "boston_licensing_board_licenses", licence)))
                .hasValueSatisfying(h -> assertThat(h.source()).isEqualTo("osm"));
        assertThat(PlaceFacts.best(Map.of("osm_geofabrik_massachusetts", unreadable,
                "boston_licensing_board_licenses", licence)))
                .as("OSM hours that do not read fall back to the licence")
                .hasValueSatisfying(h -> assertThat(h.source()).isEqualTo("boston_licence"));
        assertThat(PlaceFacts.best(Map.of())).isEmpty();
    }

    private static Optional<WeeklyHours> perDay(String... days) {
        var written = new EnumMap<DayOfWeek, String>(DayOfWeek.class);
        List<DayOfWeek> order = List.of(DayOfWeek.values());
        for (int d = 0; d < 7; d++) {
            written.put(order.get(d), days[d]);
        }
        return WeeklyHours.perDay(written);
    }
}
