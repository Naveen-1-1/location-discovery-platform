package io.github.naveen11.placemap.livehub.feeds;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.livehub.state.FeedUpdate;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** MBTA V3 records, trimmed from the real streams of 2026-10-11, into the same items as the open files give. */
class MbtaV3Test {

    private static final JsonMapper JSON = new JsonMapper();
    private static final Duration TTL = Duration.ofSeconds(15);
    private static final long NOW = 1_791_749_600_000L; // 2026-10-11T16:13:20-04:00
    private static final MbtaStatic.Names NAMES = new MbtaStatic.Names(Map.of(), Map.of(),
            Map.of("70019", "place-chncl"));

    static final String BUS = """
            {"attributes":{"bearing":null,"current_status":"STOPPED_AT","label":"0772","latitude":42.251811,
             "longitude":-71.005118,"updated_at":"2026-10-11T16:53:51-04:00"},"id":"y0772",
             "relationships":{"route":{"data":{"id":"225","type":"route"}},"stop":{"data":{"id":"32001","type":"stop"}}},
             "type":"vehicle"}""";

    static final String ELEVATOR = """
            {"attributes":{"active_period":[{"end":null,"start":"2026-10-11T07:28:01-04:00"}],"effect":"ELEVATOR_CLOSURE",
             "header":"Chinatown Elevator 876 is unavailable due to maintenance.","severity":3,
             "informed_entity":[{"stop":"70019","route":"Orange","facility":"876"},
                                {"stop":"place-chncl","route":"Orange","facility":"876"}]},
             "id":"700001","type":"alert"}""";

    static final String LATER = """
            {"attributes":{"active_period":[{"end":"2026-10-20T00:00:00-04:00","start":"2026-10-19T00:00:00-04:00"}],
             "effect":"DETOUR","header":"Next week","severity":5,"informed_entity":[{"stop":"1"}]},
             "id":"700002","type":"alert"}""";

    @Test
    void aVehicleBecomesTheSameItemAsFromTheOpenFile() {
        assertThat(new String(MbtaV3.vehicle(JSON.readTree(BUS)), StandardCharsets.UTF_8)).isEqualTo(
                "{\"id\":\"y0772\",\"route\":\"225\",\"label\":\"0772\",\"lat\":42.25181,\"lon\":-71.00512,"
                        + "\"bearing\":null,\"status\":\"STOPPED_AT\",\"stop\":\"32001\",\"updated\":1791752031}");
    }

    @Test
    void vehicleEventsBecomeOneUpdateEach() {
        MbtaStream.Handler handler = MbtaV3.vehicles();
        var reset = (FeedUpdate.Replace) handler.reset(JSON.readTree("[" + BUS + "]"), NOW, TTL);
        assertThat(reset.items()).containsOnlyKeys("y0772");

        assertThat(handler.change("update", JSON.readTree(BUS), NOW, TTL)).singleElement()
                .isInstanceOfSatisfying(FeedUpdate.Upsert.class, u -> assertThat(u.id()).isEqualTo("y0772"));
        assertThat(handler.change("remove", JSON.readTree("{\"id\":\"y2040\",\"type\":\"vehicle\"}"), NOW, TTL))
                .singleElement().isInstanceOfSatisfying(FeedUpdate.Remove.class, r -> assertThat(r.id()).isEqualTo("y2040"));
        assertThat(handler.tick(NOW, TTL)).isInstanceOf(FeedUpdate.Touch.class);
    }

    @Test
    void alertsAreFiledPerStopLikeTheOpenFileAndFollowEachChange() {
        MbtaStream.Handler handler = MbtaV3.alerts(() -> NAMES);

        var reset = (FeedUpdate.Replace) handler.reset(JSON.readTree("[" + ELEVATOR + "," + LATER + "]"), NOW, TTL);
        assertThat(reset.items()).as("next week's detour is not in effect yet").containsOnlyKeys("70019", "place-chncl");
        assertThat(new String(reset.items().get("place-chncl"), StandardCharsets.UTF_8)).isEqualTo(
                "{\"id\":\"place-chncl\",\"elevator\":true,\"escalator\":false,\"alerts\":[{\"id\":\"700001\","
                        + "\"effect\":\"ELEVATOR_CLOSURE\",\"detail\":\"ELEVATOR_CLOSURE\",\"severity\":\"WARNING\","
                        + "\"header\":\"Chinatown Elevator 876 is unavailable due to maintenance.\"}]}");

        List<FeedUpdate> removed = handler.change("remove", JSON.readTree("{\"id\":\"700001\",\"type\":\"alert\"}"), NOW, TTL);
        assertThat(removed).singleElement().isInstanceOfSatisfying(FeedUpdate.Replace.class,
                r -> assertThat(r.items()).isEmpty());

        long nextWeek = NOW + Duration.ofDays(8).toMillis();
        assertThat(((FeedUpdate.Replace) handler.tick(nextWeek, TTL)).items())
                .as("the tick catches an alert whose period began without an event").containsOnlyKeys("1");
    }

    @Test
    void severityReadsLikeTheOpenFiles() {
        assertThat(MbtaV3.severity(1)).isEqualTo("INFO");
        assertThat(MbtaV3.severity(3)).isEqualTo("WARNING");
        assertThat(MbtaV3.severity(7)).isEqualTo("SEVERE");
    }
}
