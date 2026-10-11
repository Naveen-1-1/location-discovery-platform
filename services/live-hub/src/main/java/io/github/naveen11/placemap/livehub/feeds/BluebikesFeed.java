package io.github.naveen11.placemap.livehub.feeds;

import io.github.naveen11.placemap.livehub.LiveMetrics;
import io.github.naveen11.placemap.livehub.state.FeedUpdate;
import io.github.naveen11.placemap.livehub.state.Topic;
import io.github.naveen11.placemap.livehub.state.UpdateSink;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bluebikes GBFS {@code station_status} (no key needed), polled at the feed's own ttl, 60 s today (plan, showcase 2).
 * Each poll hands the whole dock list to the snapshot writer, which works out what changed.
 *
 * <p>An item is the counts riders care about. {@code last_reported} is left out: it moves every time a dock checks
 * in, and would turn every unchanged dock into a change.
 */
public final class BluebikesFeed implements PolledFeed {

    private static final Logger log = LoggerFactory.getLogger(BluebikesFeed.class);
    private static final JsonMapper JSON = new JsonMapper();

    /** One dock's live counts, as sent to clients. Field order is the JSON order. */
    record DockStatus(String id, int bikes, int ebikes, int docks, boolean installed, boolean renting,
            boolean returning) {
    }

    private final RestClient http;
    private final String url;
    private final Duration minInterval;
    private final Duration maxInterval;
    private final UpdateSink writer;
    private final LiveMetrics metrics;
    private final LongSupplier millisClock;
    private volatile Duration interval;

    public BluebikesFeed(RestClient http, String url, Duration minInterval, Duration maxInterval, UpdateSink writer,
            LiveMetrics metrics, LongSupplier millisClock) {
        this.http = http;
        this.url = url;
        this.minInterval = minInterval;
        this.maxInterval = maxInterval;
        this.writer = writer;
        this.metrics = metrics;
        this.millisClock = millisClock;
        this.interval = clamp(Topic.BIKES.defaultTtl());
    }

    @Override
    public String name() {
        return "bluebikes";
    }

    @Override
    public Duration initialInterval() {
        return interval;
    }

    @Override
    public Duration poll() throws InterruptedException {
        long now = millisClock.getAsLong();
        FeedUpdate update;
        try {
            String body = http.get().uri(url).retrieve().body(String.class);
            update = parse(body, now);
            interval = clamp(((FeedUpdate.Replace) update).ttl());
            metrics.poll(Topic.BIKES, "ok");
        } catch (RestClientException | JacksonException | IllegalArgumentException e) {
            log.warn("bluebikes: station_status failed: {}", e.toString());
            update = new FeedUpdate.Failed(Topic.BIKES, e.getClass().getSimpleName(), now);
            metrics.poll(Topic.BIKES, "failed");
        }
        writer.submit(update); // a dropped update is counted there; the next poll carries the whole state again
        return interval;
    }

    /** The feed's body as one {@link FeedUpdate.Replace}; package-private for tests. */
    static FeedUpdate.Replace parse(String body, long fetchedAtMillis) {
        JsonNode root = JSON.readTree(body);
        JsonNode stations = root.path("data").path("stations");
        if (!stations.isArray()) {
            throw new IllegalArgumentException("station_status has no data.stations array");
        }
        Map<String, byte[]> items = new HashMap<>();
        for (JsonNode station : stations.values()) {
            String id = station.path("station_id").asString(null);
            if (id == null || id.isBlank()) {
                continue;
            }
            var status = new DockStatus(id,
                    station.path("num_bikes_available").asInt(0),
                    station.path("num_ebikes_available").asInt(0),
                    station.path("num_docks_available").asInt(0),
                    flag(station.path("is_installed")),
                    flag(station.path("is_renting")),
                    flag(station.path("is_returning")));
            items.put(id, JSON.writeValueAsBytes(status));
        }
        long ttlSeconds = root.path("ttl").asLong(Topic.BIKES.defaultTtl().toSeconds());
        return new FeedUpdate.Replace(Topic.BIKES, items, fetchedAtMillis, Duration.ofSeconds(Math.max(1, ttlSeconds)));
    }

    /** GBFS 1.1 sends 0/1; later versions send booleans. */
    private static boolean flag(JsonNode value) {
        return value.isBoolean() ? value.asBoolean() : value.asInt(0) != 0;
    }

    private Duration clamp(Duration ttl) {
        if (ttl.compareTo(minInterval) < 0) {
            return minInterval;
        }
        return ttl.compareTo(maxInterval) > 0 ? maxInterval : ttl;
    }
}
