package io.github.naveen11.placemap.livehub.feeds;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns MBTA's GTFS-Realtime JSON files into live-hub items, shaped for the map: what a rider at a stop needs to
 * know, not the feed's full structure. Field order in each record is the JSON order.
 */
public final class MbtaParsers {

    /** Departures kept per stop or station. */
    static final int DEPARTURES_PER_STOP = 8;

    private static final JsonMapper JSON = new JsonMapper();

    /** One alert as shown at a stop. */
    record StopAlert(String id, String effect, String detail, String severity, String header) {
    }

    /** Everything in effect at one stop; {@code elevator}/{@code escalator} flag an outage there. */
    record StopAlerts(String id, boolean elevator, boolean escalator, List<StopAlert> alerts) {
    }

    record Vehicle(String id, String route, String label, double lat, double lon, Integer bearing, String status,
            String stop, long updated) {
    }

    record Departure(String route, String routeName, int direction, String destination, long time) {
    }

    record StopDepartures(String id, List<Departure> next) {
    }

    private MbtaParsers() {
    }

    /**
     * Alerts in effect now, filed under every stop they name. A platform's alert is also filed under its station, so
     * a station place shows its elevators' outages.
     */
    public static MbtaRealtimeFeed.Parser alerts(Supplier<MbtaStatic.Names> names) {
        return (root, now) -> {
            Map<String, LinkedHashMap<String, StopAlert>> byStop = new HashMap<>();
            for (JsonNode entity : root.path("entity").values()) {
                JsonNode alert = entity.path("alert");
                if (!activeNow(alert.path("active_period"), now)) {
                    continue;
                }
                var shown = new StopAlert(entity.path("id").asString(), alert.path("effect").asString(null),
                        alert.path("effect_detail").asString(null), alert.path("severity_level").asString(null),
                        english(alert.path("header_text")));
                var stops = new LinkedHashSet<String>();
                for (JsonNode informed : alert.path("informed_entity").values()) {
                    String stop = informed.path("stop_id").asString(null);
                    if (stop != null) {
                        stops.add(stop);
                        stops.add(names.get().station(stop));
                    }
                }
                for (String stop : stops) {
                    byStop.computeIfAbsent(stop, s -> new LinkedHashMap<>()).put(shown.id(), shown);
                }
            }
            return byStop(byStop);
        };
    }

    /** One item per stop: its alerts sorted by id, and whether any of them is an elevator or escalator outage. */
    static Map<String, byte[]> byStop(Map<String, ? extends Map<String, StopAlert>> alertsPerStop) {
        var items = new HashMap<String, byte[]>();
        alertsPerStop.forEach((stop, alerts) -> {
            var list = new ArrayList<>(alerts.values());
            list.sort(Comparator.comparing(StopAlert::id));
            boolean elevator = list.stream().anyMatch(a -> "ELEVATOR_CLOSURE".equals(a.detail()));
            boolean escalator = list.stream().anyMatch(a -> "ESCALATOR_CLOSURE".equals(a.detail()));
            items.put(stop, JSON.writeValueAsBytes(new StopAlerts(stop, elevator, escalator, list)));
        });
        return items;
    }

    /** Every vehicle with a position, rounded to about 1 m. */
    public static MbtaRealtimeFeed.Parser vehicles() {
        return (root, now) -> {
            var items = new HashMap<String, byte[]>();
            for (JsonNode entity : root.path("entity").values()) {
                JsonNode v = entity.path("vehicle");
                JsonNode position = v.path("position");
                String id = v.path("vehicle").path("id").asString(entity.path("id").asString(null));
                if (id == null || !position.has("latitude")) {
                    continue;
                }
                JsonNode bearing = position.path("bearing");
                var vehicle = new Vehicle(id, v.path("trip").path("route_id").asString(null),
                        v.path("vehicle").path("label").asString(null), round(position.path("latitude").asDouble()),
                        round(position.path("longitude").asDouble()), bearing.isNumber() ? (int) Math.round(bearing.asDouble()) : null,
                        v.path("current_status").asString(null), v.path("stop_id").asString(null),
                        v.path("timestamp").asLong(0));
                items.put(id, JSON.writeValueAsBytes(vehicle));
            }
            return items;
        };
    }

    /**
     * The next departures at each stop, and at each station from all its platforms, soonest first. A stop's last
     * stop on a trip has an arrival and no departure: nobody boards there, so it is left out.
     */
    public static MbtaRealtimeFeed.Parser departures(Supplier<MbtaStatic.Names> names) {
        return (root, now) -> {
            MbtaStatic.Names n = names.get();
            Map<String, List<Departure>> byStop = new HashMap<>();
            for (JsonNode entity : root.path("entity").values()) {
                JsonNode trip = entity.path("trip_update").path("trip");
                String route = trip.path("route_id").asString(null);
                if (route == null || "CANCELED".equals(trip.path("schedule_relationship").asString(""))) {
                    continue;
                }
                int direction = trip.path("direction_id").asInt(0);
                for (JsonNode update : entity.path("trip_update").path("stop_time_update").values()) {
                    long time = update.path("departure").path("time").asLong(0);
                    String stop = update.path("stop_id").asString(null);
                    if (time < now || stop == null || "SKIPPED".equals(update.path("schedule_relationship").asString(""))) {
                        continue;
                    }
                    var departure = new Departure(route, n.route(route), direction, n.destination(route, direction), time);
                    byStop.computeIfAbsent(stop, s -> new ArrayList<>()).add(departure);
                    String station = n.station(stop);
                    if (!station.equals(stop)) {
                        byStop.computeIfAbsent(station, s -> new ArrayList<>()).add(departure);
                    }
                }
            }
            var items = new HashMap<String, byte[]>();
            byStop.forEach((stop, list) -> {
                list.sort(Comparator.comparingLong(Departure::time).thenComparing(Departure::route));
                var next = List.copyOf(list.subList(0, Math.min(DEPARTURES_PER_STOP, list.size())));
                items.put(stop, JSON.writeValueAsBytes(new StopDepartures(stop, next)));
            });
            return items;
        };
    }

    private static boolean activeNow(JsonNode periods, long now) {
        if (!periods.isArray() || periods.isEmpty()) {
            return true; // no period means "in effect until removed"
        }
        for (JsonNode period : periods.values()) {
            long start = period.path("start").asLong(0);
            long end = period.path("end").asLong(Long.MAX_VALUE);
            if (start <= now && now < end) {
                return true;
            }
        }
        return false;
    }

    private static String english(JsonNode text) {
        String any = null;
        for (JsonNode t : text.path("translation").values()) {
            String language = t.path("language").asString("en");
            if (language.equals("en")) {
                return t.path("text").asString(null);
            }
            if (any == null) {
                any = t.path("text").asString(null);
            }
        }
        return any;
    }

    static double round(double degrees) {
        return Math.round(degrees * 100_000) / 100_000.0;
    }
}
