package io.github.naveen11.placemap.livehub.feeds;

import com.launchdarkly.eventsource.ConnectStrategy;
import com.launchdarkly.eventsource.ErrorStrategy;
import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.FaultEvent;
import com.launchdarkly.eventsource.MessageEvent;
import com.launchdarkly.eventsource.RetryDelayStrategy;
import com.launchdarkly.eventsource.StartedEvent;
import com.launchdarkly.eventsource.StreamClosedByCallerException;
import com.launchdarkly.eventsource.StreamEvent;
import com.launchdarkly.eventsource.StreamHttpErrorException;
import io.github.naveen11.placemap.livehub.LiveMetrics;
import io.github.naveen11.placemap.livehub.state.FeedUpdate;
import io.github.naveen11.placemap.livehub.state.Topic;
import io.github.naveen11.placemap.livehub.state.UpdateSink;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One MBTA V3 streaming resource ({@code GET /alerts} or {@code /vehicles} with {@code Accept: text/event-stream}),
 * the plan's primary MBTA source, read with LaunchDarkly's SSE client. The stream opens with a {@code reset} holding
 * every record, then sends {@code add}, {@code update} and {@code remove} events as records change.
 *
 * <ul>
 *   <li><b>Its own thread</b> reads the stream for as long as it lives. The client reconnects by itself after a few
 *       seconds, backing off to about a minute while MBTA keeps refusing.
 *   <li><b>Healthy</b> from the first {@code reset} until the stream ends. While it is healthy the open-file feed for
 *       the same topic stays idle; once it is not, that feed takes over (plan: GTFS-RT only while V3 is unhealthy).
 *   <li><b>A quiet stream is not a stale one.</b> Alerts can go minutes without an event, so every tick (15 s) while
 *       connected counts as an answer. A stream silent past {@code idleTimeout} hits the client's read timeout and is
 *       reconnected, in case the connection died without either side noticing.
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
    private static final String USER_AGENT = "place-discovery-map-live-hub (+https://github.com/Naveen-1-1/place-discovery-map)";

    private final EventSource source;
    private final Topic topic;
    private final Handler handler;
    private final UpdateSink writer;
    private final LiveMetrics metrics;
    private final Duration tick;
    private final LongSupplier millisClock;

    private volatile boolean healthy;

    public MbtaStream(URI uri, String apiKey, Topic topic, Handler handler, UpdateSink writer, LiveMetrics metrics,
            Duration tick, Duration idleTimeout, LongSupplier millisClock) {
        this.source = new EventSource.Builder(ConnectStrategy.http(uri)
                        .header("x-api-key", apiKey)
                        .header("User-Agent", USER_AGENT)
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(idleTimeout.toMillis(), TimeUnit.MILLISECONDS))
                .errorStrategy(ErrorStrategy.alwaysContinue())
                // 2–4 s after a failure, doubling while MBTA keeps refusing; a stream that stayed up a minute starts
                // over.
                .retryDelayStrategy(RetryDelayStrategy.defaultStrategy()
                        .initialDelay(4, TimeUnit.SECONDS)
                        .maxDelay(1, TimeUnit.MINUTES)
                        .backoffMultiplier(2)
                        .jitterMultiplier(0.5f))
                .retryDelayResetThreshold(1, TimeUnit.MINUTES)
                .build();
        this.topic = topic;
        this.handler = handler;
        this.writer = writer;
        this.metrics = metrics;
        this.tick = tick;
        this.millisClock = millisClock;
        metrics.topicGauge("placemap.live.stream.healthy", "1 while the topic's MBTA V3 stream is connected", topic,
                () -> healthy ? 1 : 0);
    }

    /** Starts reading on a thread of {@code reader}, which this stream keeps for as long as it lives. */
    public void start(Executor reader) {
        reader.execute(this::read);
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

    /** The tick: tells the writer the stream is still there. */
    @Override
    public Duration poll() throws InterruptedException {
        if (healthy) {
            writer.submit(handler.tick(millisClock.getAsLong(), tick));
        }
        return tick;
    }

    private void read() {
        try {
            for (StreamEvent event : source.anyEvents()) {
                handle(event);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // shutting down
        }
    }

    /** One event from the client; package-private for tests. */
    void handle(StreamEvent event) throws InterruptedException {
        switch (event) {
            case StartedEvent _ -> metrics.poll(topic, "stream_connected");
            case MessageEvent message -> dispatch(message.getEventName(), message.getData());
            case FaultEvent fault -> {
                healthy = false;
                if (fault.getCause() instanceof StreamHttpErrorException refused) {
                    metrics.poll(topic, "stream_refused");
                    log.warn("{}: MBTA answered HTTP {}", name(), refused.getCode());
                } else if (!(fault.getCause() instanceof StreamClosedByCallerException)) {
                    log.warn("{}: stream ended: {}", name(), fault.getCause().toString());
                }
            }
            default -> {
                // comments and retry hints need nothing from us
            }
        }
    }

    private void dispatch(String event, String data) throws InterruptedException {
        long now = millisClock.getAsLong();
        JsonNode node;
        try {
            node = JSON.readTree(data);
        } catch (JacksonException e) {
            log.warn("{}: unreadable {} event skipped: {}", name(), event, e.getOriginalMessage());
            return;
        }
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
        healthy = false;
        source.close();
    }
}
