package io.github.naveen11.placemap.livehub.feeds;

import io.github.naveen11.placemap.livehub.LiveMetrics;
import io.github.naveen11.placemap.livehub.state.FeedUpdate;
import io.github.naveen11.placemap.livehub.state.Topic;
import io.github.naveen11.placemap.livehub.state.UpdateSink;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One of MBTA's GTFS-Realtime files in their JSON form ({@code cdn.mbta.com/realtime/*_enhanced.json}, no key
 * needed), polled on a fixed interval. These are the plan's fallback feeds; they carry the same data as the V3
 * streams (ADR 0041).
 *
 * <ul>
 *   <li>Asks for gzip, which shrinks the 2.2 MB trip updates to about 250 KB, and sends the last ETag so an unchanged
 *       file costs one short 304. A 304 still counts as an answer: the last items are handed over again, and the
 *       writer finds nothing changed.
 *   <li>A feed with a demand check (departures) polls only while someone has asked for it recently; otherwise its
 *       trigger passes without a request and the topic goes stale, which is the truth.
 * </ul>
 */
public final class MbtaRealtimeFeed implements PolledFeed {

    /** Turns a parsed file into the topic's items. */
    @FunctionalInterface
    public interface Parser {
        Map<String, byte[]> items(JsonNode root, long nowSeconds);
    }

    private static final Logger log = LoggerFactory.getLogger(MbtaRealtimeFeed.class);
    private static final JsonMapper JSON = new JsonMapper();

    private final RestClient http;
    private final String url;
    private final Topic topic;
    private final Duration interval;
    private final Parser parser;
    private final BooleanSupplier wanted;
    private final UpdateSink writer;
    private final LiveMetrics metrics;
    private final LongSupplier millisClock;
    private String etag;              // the poll thread's own: polls never overlap
    private Map<String, byte[]> last;

    public MbtaRealtimeFeed(RestClient http, String url, Topic topic, Duration interval, Parser parser,
            BooleanSupplier wanted, UpdateSink writer, LiveMetrics metrics, LongSupplier millisClock) {
        this.http = http;
        this.url = url;
        this.topic = topic;
        this.interval = interval;
        this.parser = parser;
        this.wanted = wanted;
        this.writer = writer;
        this.metrics = metrics;
        this.millisClock = millisClock;
    }

    @Override
    public String name() {
        return "mbta-" + topic.wire();
    }

    @Override
    public Duration initialInterval() {
        return interval;
    }

    @Override
    public Duration poll() throws InterruptedException {
        if (!wanted.getAsBoolean()) {
            metrics.poll(topic, "not_wanted");
            return interval;
        }
        long now = millisClock.getAsLong();
        FeedUpdate update;
        try {
            Map<String, byte[]> items = fetch(now / 1000);
            last = items;
            update = new FeedUpdate.Replace(topic, items, now, interval);
            metrics.poll(topic, "ok");
        } catch (RestClientException | JacksonException | IllegalArgumentException e) {
            log.warn("{}: {} failed: {}", name(), url, e.toString());
            update = new FeedUpdate.Failed(topic, e.getClass().getSimpleName(), now);
            metrics.poll(topic, "failed");
        }
        writer.submit(update);
        return interval;
    }

    private Map<String, byte[]> fetch(long nowSeconds) {
        return http.get().uri(url)
                .header("Accept-Encoding", "gzip")
                .headers(h -> {
                    if (etag != null && last != null) {
                        h.setIfNoneMatch(etag);
                    }
                })
                .exchange((request, response) -> {
                    if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_MODIFIED)) {
                        return last;
                    }
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalArgumentException(url + " answered " + response.getStatusCode());
                    }
                    etag = response.getHeaders().getETag();
                    try (InputStream body = decoded(response.getBody(), response.getHeaders().getFirst("Content-Encoding"))) {
                        return parser.items(JSON.readTree(body), nowSeconds);
                    }
                });
    }

    private static InputStream decoded(InputStream body, String encoding) throws IOException {
        return encoding != null && encoding.equalsIgnoreCase("gzip") ? new GZIPInputStream(body) : body;
    }
}
