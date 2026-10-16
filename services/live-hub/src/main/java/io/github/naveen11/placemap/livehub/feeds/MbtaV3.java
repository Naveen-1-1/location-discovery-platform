package io.github.naveen11.placemap.livehub.feeds;

import io.github.naveen11.placemap.livehub.feeds.MbtaParsers.StopAlert;
import io.github.naveen11.placemap.livehub.feeds.MbtaParsers.Vehicle;
import io.github.naveen11.placemap.livehub.state.FeedUpdate;
import io.github.naveen11.placemap.livehub.state.Topic;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MBTA V3 records (JSON:API) into the same items the open realtime files give, so clients cannot tell which source
 * is in use.
 */
public final class MbtaV3 {

    private static final JsonMapper JSON = new JsonMapper();

    private MbtaV3() {
    }

    /** Vehicles change one at a time: each event becomes one upsert or removal. */
    public static MbtaStream.Handler vehicles(Area area) {
        return new MbtaStream.Handler() {
            @Override
            public FeedUpdate reset(JsonNode resources, long now, Duration ttl) {
                var items = new HashMap<String, byte[]>();
                for (JsonNode resource : resources.values()) {
                    byte[] json = vehicle(resource, area);
                    if (json != null) {
                        items.put(resource.path("id").asString(), json);
                    }
                }
                return new FeedUpdate.Replace(Topic.VEHICLES, items, now, ttl);
            }

            @Override
            public List<FeedUpdate> change(String event, JsonNode resource, long now, Duration ttl) {
                String id = resource.path("id").asString(null);
                if (id == null) {
                    return List.of();
                }
                byte[] json = event.equals("remove") ? null : vehicle(resource, area); // left the area: gone
                return List.of(json == null ? new FeedUpdate.Remove(Topic.VEHICLES, id, now, ttl)
                        : new FeedUpdate.Upsert(Topic.VEHICLES, id, json, now, ttl));
            }

            @Override
            public FeedUpdate tick(long now, Duration ttl) {
                return new FeedUpdate.Touch(Topic.VEHICLES, now, ttl);
            }
        };
    }

    /** One vehicle's item, or null without a position or outside the area. */
    static byte[] vehicle(JsonNode resource, Area area) {
        JsonNode a = resource.path("attributes");
        if (!a.path("latitude").isNumber() || !a.path("longitude").isNumber()) {
            return null;
        }
        double lat = MbtaParsers.round(a.path("latitude").asDouble());
        double lon = MbtaParsers.round(a.path("longitude").asDouble());
        return area.contains(lat, lon)
                ? JSON.writeValueAsBytes(new Vehicle(resource.path("id").asString(), related(resource, "route"), lat, lon))
                : null;
    }

    /**
     * Alerts are filed per stop, and one alert can name dozens of stops, so the handler keeps every alert and hands
     * the writer the whole per-stop picture after each change (the writer sends on only what differs). The tick does
     * the same, which also catches alerts whose active period starts or ends without an event.
     */
    public static MbtaStream.Handler alerts(Supplier<MbtaStatic.Names> names) {
        return new MbtaStream.Handler() {
            private final Map<String, JsonNode> alerts = new LinkedHashMap<>(); // guarded by this

            @Override
            public synchronized FeedUpdate reset(JsonNode resources, long now, Duration ttl) {
                alerts.clear();
                for (JsonNode resource : resources.values()) {
                    alerts.put(resource.path("id").asString(), resource.path("attributes"));
                }
                return snapshot(now, ttl);
            }

            @Override
            public synchronized List<FeedUpdate> change(String event, JsonNode resource, long now, Duration ttl) {
                String id = resource.path("id").asString(null);
                if (id == null) {
                    return List.of();
                }
                if (event.equals("remove")) {
                    alerts.remove(id);
                } else {
                    alerts.put(id, resource.path("attributes"));
                }
                return List.of(snapshot(now, ttl));
            }

            @Override
            public synchronized FeedUpdate tick(long now, Duration ttl) {
                return snapshot(now, ttl);
            }

            private FeedUpdate snapshot(long nowMillis, Duration ttl) {
                long now = nowMillis / 1000;
                Map<String, LinkedHashMap<String, StopAlert>> byStop = new HashMap<>();
                alerts.forEach((id, a) -> {
                    if (!activeNow(a.path("active_period"), now)) {
                        return;
                    }
                    String effect = a.path("effect").asString(null);
                    var shown = new StopAlert(id, effect, effect, severity(a.path("severity").asInt(0)),
                            a.path("header").asString(null));
                    var stops = new LinkedHashSet<String>();
                    for (JsonNode informed : a.path("informed_entity").values()) {
                        String stop = informed.path("stop").asString(null);
                        if (stop != null) {
                            stops.add(stop);
                            stops.add(names.get().station(stop));
                        }
                    }
                    for (String stop : stops) {
                        byStop.computeIfAbsent(stop, s -> new LinkedHashMap<>()).put(id, shown);
                    }
                });
                return new FeedUpdate.Replace(Topic.ALERTS, MbtaParsers.byStop(byStop), nowMillis, ttl);
            }
        };
    }

    /** V3's 0–10 severity as the open files' levels, so both sources read alike. */
    static String severity(int level) {
        return level >= 7 ? "SEVERE" : level >= 3 ? "WARNING" : "INFO";
    }

    private static boolean activeNow(JsonNode periods, long now) {
        if (!periods.isArray() || periods.isEmpty()) {
            return true;
        }
        for (JsonNode period : periods.values()) {
            String start = period.path("start").asString(null);
            String end = period.path("end").asString(null);
            if ((start == null || OffsetDateTime.parse(start).toEpochSecond() <= now)
                    && (end == null || now < OffsetDateTime.parse(end).toEpochSecond())) {
                return true;
            }
        }
        return false;
    }

    private static String related(JsonNode resource, String name) {
        return resource.path("relationships").path(name).path("data").path("id").asString(null);
    }
}
