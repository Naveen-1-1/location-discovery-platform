package io.github.naveen11.placemap.livehub.feeds;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** MBTA's realtime JSON, trimmed from the real files of 2026-10-11, into what the map shows. */
class MbtaParsersTest {

    private static final JsonMapper JSON = new JsonMapper();
    private static final long NOW = 1_791_749_600L;
    private static final MbtaStatic.Names NAMES = new MbtaStatic.Names(
            Map.of("Red", "Red Line", "741", "SL1"),
            Map.of("Red/0", "Ashmont/Braintree", "Red/1", "Alewife"),
            Map.of("70067", "place-harsq", "70068", "place-harsq", "74612", "place-crtst"));

    @Test
    void alertsAreFiledUnderEachStopTheyNameAndItsStation() {
        JsonNode root = JSON.readTree("""
                {"header":{"timestamp":1791749580},"entity":[
                 {"id":"691524","alert":{"effect":"ACCESSIBILITY_ISSUE","effect_detail":"ESCALATOR_CLOSURE",
                  "severity_level":"INFO","active_period":[{"start":1791700000}],
                  "header_text":{"translation":[{"text":"Courthouse Escalator 415 unavailable","language":"en"}]},
                  "informed_entity":[{"stop_id":"74612","route_id":"741","facility_id":"415"},
                                     {"stop_id":"place-crtst","route_id":"741","facility_id":"415"}]}},
                 {"id":"700001","alert":{"effect":"DETOUR","severity_level":"WARNING",
                  "active_period":[{"start":1791900000,"end":1791990000}],
                  "header_text":{"translation":[{"text":"Later detour","language":"en"}]},
                  "informed_entity":[{"stop_id":"1"}]}}]}""");

        Map<String, byte[]> items = MbtaParsers.alerts(() -> NAMES).items(root, NOW);

        assertThat(items).containsOnlyKeys("74612", "place-crtst"); // the later detour is not in effect yet
        assertThat(text(items.get("place-crtst"))).isEqualTo("{\"id\":\"place-crtst\",\"elevator\":false,"
                + "\"escalator\":true,\"alerts\":[{\"id\":\"691524\",\"effect\":\"ACCESSIBILITY_ISSUE\","
                + "\"detail\":\"ESCALATOR_CLOSURE\",\"severity\":\"INFO\",\"header\":\"Courthouse Escalator 415 unavailable\"}]}");
    }

    @Test
    void vehiclesKeepTheirRouteAndARoundedPosition() {
        JsonNode root = JSON.readTree("""
                {"entity":[{"id":"1814","vehicle":{"current_status":"IN_TRANSIT_TO",
                  "position":{"latitude":42.16013717651367,"longitude":-71.23456573486328,"bearing":181.4,"speed":30.0},
                  "stop_id":"FB-0166-S","timestamp":1791749583,
                  "trip":{"route_id":"CR-Franklin","direction_id":1},"vehicle":{"id":"1814","label":"1814"}}},
                 {"id":"y1","vehicle":{"vehicle":{"id":"y1"}}}]}""");

        Map<String, byte[]> items = MbtaParsers.vehicles().items(root, NOW);

        assertThat(items).containsOnlyKeys("1814"); // no position, no pin
        assertThat(text(items.get("1814"))).isEqualTo("{\"id\":\"1814\",\"route\":\"CR-Franklin\",\"label\":\"1814\","
                + "\"lat\":42.16014,\"lon\":-71.23457,\"bearing\":181,\"status\":\"IN_TRANSIT_TO\",\"stop\":\"FB-0166-S\","
                + "\"updated\":1791749583}");
    }

    @Test
    void departuresAreListedPerPlatformAndPerStationSoonestFirst() {
        JsonNode root = JSON.readTree("""
                {"entity":[
                 {"id":"a","trip_update":{"trip":{"route_id":"Red","direction_id":0,"trip_id":"77916370"},
                  "stop_time_update":[
                   {"stop_id":"70067","arrival":{"time":1791749500},"departure":{"time":1791749550}},
                   {"stop_id":"70067","arrival":{"time":1791749758},"departure":{"time":1791749848}},
                   {"stop_id":"70069","arrival":{"time":1791750000}}]}},
                 {"id":"b","trip_update":{"trip":{"route_id":"Red","direction_id":1},
                  "stop_time_update":[{"stop_id":"70068","departure":{"time":1791749700}},
                                      {"stop_id":"70068","schedule_relationship":"SKIPPED","departure":{"time":1791749900}}]}},
                 {"id":"c","trip_update":{"trip":{"route_id":"Red","direction_id":1,"schedule_relationship":"CANCELED"},
                  "stop_time_update":[{"stop_id":"70068","departure":{"time":1791749650}}]}}]}""");

        Map<String, byte[]> items = MbtaParsers.departures(() -> NAMES).items(root, NOW);

        // Past departures, last stops (arrival only), skipped stops and canceled trips are all left out.
        assertThat(items).containsOnlyKeys("70067", "70068", "place-harsq");
        assertThat(text(items.get("place-harsq"))).isEqualTo("{\"id\":\"place-harsq\",\"next\":["
                + "{\"route\":\"Red\",\"routeName\":\"Red Line\",\"direction\":1,\"destination\":\"Alewife\",\"time\":1791749700},"
                + "{\"route\":\"Red\",\"routeName\":\"Red Line\",\"direction\":0,\"destination\":\"Ashmont/Braintree\",\"time\":1791749848}]}");
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
