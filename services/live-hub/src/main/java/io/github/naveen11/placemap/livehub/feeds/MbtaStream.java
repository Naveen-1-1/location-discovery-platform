package io.github.naveen11.placemap.livehub.feeds;

import io.github.naveen11.placemap.livehub.LiveMetrics;
import io.github.naveen11.placemap.livehub.state.FeedUpdate;
import io.github.naveen11.placemap.livehub.state.Topic;
import io.github.naveen11.placemap.livehub.state.UpdateSink;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One MBTA V3 streaming resource ({@code GET /alerts} or {@code /vehicles} with {@code Accept: text/event-stream}),
 * the plan's primary MBTA source. The stream opens with a {@code reset} holding every record, then sends
 * {@code add}, {@code update} and {@code remove} events as records change.
 *
 * <ul>
 *   <li><b>Its own virtual thread</b> reads the stream for as long as it stays open, and reconnects after a random
 *       2–7 s, backing off to about a minute while MBTA keeps refusing.
 *   <li><b>Healthy</b> from the first {@code reset} until the stream ends. While it is healthy the open-file feed for
 *       the same topic stays idle; once it is not, that feed takes over (plan: GTFS-RT only while V3 is unhealthy).
 *   <li><b>A quiet stream is not a stale one.</b> Alerts can go minutes without an event, so every tick (15 s) while
 *       connected counts as an answer. A stream that stays silent past {@code idleTimeout} is reconnected, in case the
 *       connection died without either side noticing.
 * </ul>
 *
 * <p>The API key goes in the {@code x-api-key} header and is never logged.
 */
public final class MbtaStream implements PolledFeed, AutoCloseable {

    /** Turns one resource's events into feed updates. */
    public interface Handler {
        FeedUpdate reset(JsonNode resources, long nowMillis, Duration ttl);

        List<FeedUpdate> change(String event, JsonNode resource, long nowMillis, Duration ttl);

        /** Every tick while connected: at least a {@link FeedUpdate.Touch}. */
        FeedUpdate tick(long nowMillis, Duration ttl);
    }

    private static final Logger log = LoggerFactory.getLogger(MbtaStream.class);
    private static final JsonMapper JSON = new JsonMapper();
    private static final long MAX_BACKOFF_MILLIS = 60_000;

    private final HttpClient http;
    private final URI uri;
    private final String apiKey;
    private final Topic topic;
    private final Handler handler;
    private final UpdateSink writer;
    private final LiveMetrics metrics;
    private final Duration tick;
    private final Duration idleTimeout;
    private final LongSupplier millisClock;

    private volatile boolean healthy;
    private volatile boolean closed;
    private volatile long lastEventNanos = System.nanoTime();
    private volatile Thread reader;

    public MbtaStream(HttpClient http, URI uri, String apiKey, Topic topic, Handler handler, UpdateSink writer,
            LiveMetrics metrics, Duration tick, Duration idleTimeout, LongSupplier millisClock) {
        this.http = http;
        this.uri = uri;
        this.apiKey = apiKey;
        this.topic = topic;
        this.handler = handler;
        this.writer = writer;
        this.metrics = metrics;
        this.tick = tick;
        this.idleTimeout = idleTimeout;
        this.millisClock = millisClock;
        metrics.topicGauge("placemap.live.stream.healthy", "1 while the topic's MBTA V3 stream is connected", topic,
                () -> healthy ? 1 : 0);
    }

    /** Starts reading on the stream's own virtual thread. */
    public void start() {
        reader = Thread.ofVirtual().name("mbta-stream-" + topic.wire()).start(this::run);
    }

    /** Whether the stream is connected and has sent its first {@code reset}. */
    public boolean healthy() {
        return healthy;
    }

    @Override
    public String name() {
        return "mbta-stream-" + topic.wire();
    }

    @Override
    public Duration initialInterval() {
        return tick;
    }

    /** The tick: tells the writer the stream is still there, or reconnects a stream that went silent. */
    @Override
    public Duration poll() throws InterruptedException {
        if (!healthy) {
            return tick;
        }
        if (Duration.ofNanos(System.nanoTime() - lastEventNanos).compareTo(idleTimeout) > 0) {
            log.warn("{}: no event for {}, reconnecting", name(), idleTimeout);
            Thread r = reader;
            if (r != null) {
                r.interrupt(); // the reader is this stream's own thread, so the interrupt cannot hit anyone else
            }
            return tick;
        }
        writer.submit(handler.tick(millisClock.getAsLong(), tick));
        return tick;
    }

    private void run() {
        int failures = 0;
        while (!closed) {
            long connected = System.nanoTime();
            try {
                read();
            } catch (IOException | UncheckedIOException | JacksonException | IllegalStateException e) {
                if (!closed) {
                    log.warn("{}: stream ended: {}", name(), e.toString());
                }
            } catch (InterruptedException e) {
                // close(), or the idle check: reconnect unless closed
            }
            healthy = false;
            if (closed) {
                return;
            }
            Thread.interrupted(); // a reconnect starts clean
            // A stream that stayed up for a while was fine; only quick failures in a row back off.
            failures = System.nanoTime() - connected > 60_000_000_000L ? 0 : failures + 1;
            long backoff = Math.min(MAX_BACKOFF_MILLIS, 2_000L << Math.min(Math.max(0, failures - 1), 5));
            try {
                Thread.sleep(Math.max(0, backoff - 2_000) + ThreadLocalRandom.current().nextLong(2_000, 7_001));
            } catch (InterruptedException e) {
                if (closed) {
                    return;
                }
            }
        }
    }

    private void read() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Accept", "text/event-stream")
                .header("x-api-key", apiKey)
                .header("User-Agent", "place-discovery-map-live-hub (+https://github.com/Naveen-1-1/place-discovery-map)")
                .GET()
                .build();
        HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        try (Stream<String> lines = response.body()) {
            if (response.statusCode() != 200) {
                metrics.poll(topic, "stream_refused");
                throw new IOException(uri.getPath() + " answered HTTP " + response.statusCode());
            }
            metrics.poll(topic, "stream_connected");
            lastEventNanos = System.nanoTime();
            consume(lines.iterator());
        }
    }

    /** Reads server-sent events until the stream ends; package-private for tests. */
    void consume(Iterator<String> lines) throws InterruptedException {
        String event = null;
        var data = new StringBuilder();
        while (lines.hasNext()) {
            String line = lines.next();
            if (line.isEmpty()) {
                if (event != null && !data.isEmpty()) {
                    dispatch(event, data.toString());
                }
                event = null;
                data.setLength(0);
            } else if (line.startsWith("event:")) {
                event = line.substring(6).strip();
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.substring(line.startsWith("data: ") ? 6 : 5));
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
        }
    }

    private void dispatch(String event, String data) throws InterruptedException {
        lastEventNanos = System.nanoTime();
        long now = millisClock.getAsLong();
        JsonNode node = JSON.readTree(data);
        switch (event) {
            case "reset" -> {
                writer.submit(handler.reset(node, now, tick));
                healthy = true;
            }
            case "add", "update", "remove" -> {
                for (FeedUpdate update : handler.change(event, node, now, tick)) {
                    writer.submit(update);
                }
            }
            default -> {
                // nothing else is documented
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        healthy = false;
        Thread r = reader;
        if (r != null) {
            r.interrupt();
        }
    }
}
