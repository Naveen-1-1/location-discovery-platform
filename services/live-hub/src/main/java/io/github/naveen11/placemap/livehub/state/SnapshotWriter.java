package io.github.naveen11.placemap.livehub.state;

import io.github.naveen11.placemap.concurrent.SnapshotPublisher;
import io.github.naveen11.placemap.livehub.LiveMetrics;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.springframework.core.task.AsyncTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one thread that owns all live state. Feeds hand it {@link FeedUpdate}s through a bounded queue; at
 * most once per tick (250 ms) it turns what changed into a new immutable {@link LiveSnapshot} and one frame per changed
 * key, serialized once, and publishes both through {@link SnapshotPublisher}, which hands the frames to every client
 * mailbox on this same thread.
 *
 * <ul>
 *   <li>No locks: only this thread reads or writes the mutable maps below.
 *   <li>A tick with nothing new publishes nothing, so versions only move when something changed.
 *   <li>A topic is stale once its feed has not answered for {@code staleAfterTtls} × its ttl; flipping either way
 *       sends a {@code status} event.
 *   <li>A publish that changes more than {@code wholeTopicAfter} items of one topic (a vehicle poll moves hundreds)
 *       sends that topic as one whole-topic frame instead of one frame per item: each client's mailbox then holds one
 *       key for it, not hundreds, and the client gets the topic's snapshot.
 *   <li>When the queue is full a feed waits up to {@code submitWait}, then its update is dropped and counted. Every
 *       poll carries the feed's whole state, so the next one repairs it.
 * </ul>
 */
public final class SnapshotWriter implements UpdateSink, AutoCloseable {

    private static final byte[] REMOVED = new byte[0];
    private static final JsonMapper JSON = new JsonMapper();

    /** A topic's feed health, owned by the writer thread. */
    private static final class Health {
        long updatedMillis;
        long ttlMillis;
        boolean stale = true; // until the feed first answers

        Health(Duration ttl) {
            this.ttlMillis = ttl.toMillis();
        }
    }

    /** An update and when it reached the writer's queue. */
    private record Queued(FeedUpdate update, long submittedNanos) {
    }

    private final BlockingQueue<Queued> queue;
    private final Duration submitWait;
    private final long tickNanos;
    private final int staleAfterTtls;
    private final int wholeTopicAfter;
    private final LongSupplier millisClock;
    private final LongSupplier nanoClock;
    private final LiveMetrics metrics;
    private final SnapshotPublisher<LiveSnapshot, LiveKey, Frame> publisher;
    private volatile Future<?> running;

    // Owned by the writer thread.
    private final Map<Topic, TreeMap<String, byte[]>> items = new EnumMap<>(Topic.class);
    private final Map<Topic, Health> health = new EnumMap<>(Topic.class);
    private final Map<LiveKey, byte[]> changes = new LinkedHashMap<>(); // REMOVED marks a removal
    private final EnumSet<Topic> changedTopics = EnumSet.noneOf(Topic.class);
    private final EnumSet<Topic> changedStatus = EnumSet.noneOf(Topic.class);
    private final Map<Topic, Long> waitingSince = new EnumMap<>(Topic.class); // oldest answer not yet published
    private final List<Queued> batch = new ArrayList<>();
    private LiveSnapshot current;

    public SnapshotWriter(int queueCapacity, Duration submitWait, Duration tick, int staleAfterTtls,
            int wholeTopicAfter, LongSupplier millisClock, LongSupplier nanoClock, LiveMetrics metrics) {
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.submitWait = submitWait;
        this.tickNanos = tick.toNanos();
        this.staleAfterTtls = staleAfterTtls;
        this.wholeTopicAfter = wholeTopicAfter;
        this.millisClock = millisClock;
        this.nanoClock = nanoClock;
        this.metrics = metrics;
        var views = new EnumMap<Topic, TopicView>(Topic.class);
        for (Topic topic : Topic.values()) {
            items.put(topic, new TreeMap<>());
            health.put(topic, new Health(topic.defaultTtl()));
            views.put(topic, view(topic, millisClock.getAsLong()));
        }
        this.current = new LiveSnapshot(0, millisClock.getAsLong(), Collections.unmodifiableMap(views));
        this.publisher = new SnapshotPublisher<>(current, LiveSnapshot::version);
        metrics.gauge("placemap.live.queue.size", "Feed updates waiting for the snapshot writer", queue,
                BlockingQueue::size);
        metrics.gauge("placemap.live.snapshot.version", "Version of the current snapshot", publisher,
                p -> p.current().version());
        for (Topic topic : Topic.values()) {
            metrics.topicGauge("placemap.live.feed.stale", "1 while the topic's feed is stale", topic,
                    () -> publisher.current().topic(topic).stale() ? 1 : 0);
            metrics.topicGauge("placemap.live.feed.age", "Seconds since the topic's feed last answered", topic,
                    () -> feedAgeSeconds(topic));
        }
    }

    public SnapshotPublisher<LiveSnapshot, LiveKey, Frame> publisher() {
        return publisher;
    }

    /** Starts the writer's loop on {@code thread}: a single platform thread, which owns all live state from now on. */
    public void start(AsyncTaskExecutor thread) {
        running = thread.submit(this::run);
    }

    /**
     * Hands an update to the writer, waiting up to {@code submitWait} for room.
     *
     * @return {@code false} if the queue stayed full and the update was dropped
     */
    @Override
    public boolean submit(FeedUpdate update) throws InterruptedException {
        Objects.requireNonNull(update, "update");
        if (queue.offer(new Queued(update, nanoClock.getAsLong()), submitWait.toNanos(), TimeUnit.NANOSECONDS)) {
            return true;
        }
        metrics.updateDropped(update.topic());
        return false;
    }

    /** Interrupts the loop; its executor waits for it to end when Spring shuts it down. */
    @Override
    public void close() {
        Future<?> loop = running;
        if (loop != null) {
            loop.cancel(true);
        }
    }

    private void run() {
        long nextTick = nanoClock.getAsLong() + tickNanos;
        try {
            while (true) {
                long wait = nextTick - nanoClock.getAsLong();
                if (wait > 0) {
                    Queued first = queue.poll(wait, TimeUnit.NANOSECONDS);
                    if (first != null) {
                        take(first);
                        drain();
                    }
                    continue;
                }
                tick();
                nextTick += tickNanos;
                long now = nanoClock.getAsLong();
                if (nextTick <= now) {
                    nextTick = now + tickNanos; // fell behind: skip missed ticks instead of bursting through them
                }
            }
        } catch (InterruptedException e) {
            // close(): stop.
        }
    }

    /** Applies every update waiting in the queue. Writer thread only; package-private for tests. */
    void drain() {
        queue.drainTo(batch);
        batch.forEach(this::take);
        batch.clear();
    }

    private void take(Queued queued) {
        FeedUpdate update = queued.update();
        if (!(update instanceof FeedUpdate.Failed)) { // a failure publishes nothing to wait for
            waitingSince.putIfAbsent(update.topic(), queued.submittedNanos());
        }
        apply(update);
    }

    /** Applies one update to the live state. Writer thread only; package-private so tests can drive it directly. */
    void apply(FeedUpdate update) {
        Topic topic = update.topic();
        switch (update) {
            case FeedUpdate.Replace replace -> {
                TreeMap<String, byte[]> state = items.get(topic);
                for (Iterator<Map.Entry<String, byte[]>> it = state.entrySet().iterator(); it.hasNext(); ) {
                    String id = it.next().getKey();
                    if (!replace.items().containsKey(id)) {
                        it.remove();
                        changes.put(LiveKey.item(topic, id), REMOVED);
                    }
                }
                replace.items().forEach((id, json) -> {
                    byte[] old = state.put(id, json);
                    if (old == null || !Arrays.equals(old, json)) {
                        changes.put(LiveKey.item(topic, id), json);
                    }
                });
                answered(topic, replace.fetchedAtMillis(), replace.ttl());
            }
            case FeedUpdate.Upsert upsert -> {
                byte[] old = items.get(topic).put(upsert.id(), upsert.json());
                if (old == null || !Arrays.equals(old, upsert.json())) {
                    changes.put(LiveKey.item(topic, upsert.id()), upsert.json());
                }
                answered(topic, upsert.atMillis(), upsert.ttl());
            }
            case FeedUpdate.Remove remove -> {
                if (items.get(topic).remove(remove.id()) != null) {
                    changes.put(LiveKey.item(topic, remove.id()), REMOVED);
                }
                answered(topic, remove.atMillis(), remove.ttl());
            }
            case FeedUpdate.Touch touch -> answered(topic, touch.atMillis(), touch.ttl());
            case FeedUpdate.Failed failed -> {
                // Nothing to change now: the topic turns stale by time in refreshHealth().
            }
        }
    }

    private void answered(Topic topic, long atMillis, Duration ttl) {
        Health h = health.get(topic);
        h.updatedMillis = atMillis;
        h.ttlMillis = ttl.toMillis();
        changedTopics.add(topic); // "updated" moved even if no item did
    }

    /** One tick: re-check staleness, then publish if anything changed. Writer thread only. */
    void tick() {
        refreshHealth(millisClock.getAsLong());
        if (!changedTopics.isEmpty() || !changes.isEmpty()) {
            publish();
        }
    }

    LiveSnapshot current() {
        return current;
    }

    private void refreshHealth(long nowMillis) {
        for (Map.Entry<Topic, Health> entry : health.entrySet()) {
            Health h = entry.getValue();
            boolean stale = h.updatedMillis == 0 || nowMillis - h.updatedMillis > staleAfterTtls * h.ttlMillis;
            if (stale != h.stale) {
                h.stale = stale;
                changedStatus.add(entry.getKey());
                changedTopics.add(entry.getKey());
            }
        }
    }

    private void publish() {
        long started = nanoClock.getAsLong();
        long version = current.version() + 1;
        long nowMillis = millisClock.getAsLong();
        var changed = new EnumMap<Topic, Integer>(Topic.class);
        changes.keySet().forEach(key -> changed.merge(key.topic(), 1, Integer::sum));
        var frames = new LinkedHashMap<LiveKey, Frame>();
        changes.forEach((key, json) -> {
            Topic topic = key.topic();
            if (!topic.streamed()) {
                return;
            }
            if (changed.get(topic) > wholeTopicAfter) {
                frames.putIfAbsent(LiveKey.whole(topic), Frame.wholeTopic(version, started, topic));
            } else {
                frames.put(key, new Frame(version, started, topic, deltaEvent(version, nowMillis, key, json)));
            }
        });
        changed.forEach((topic, count) -> {
            if (topic.streamed() && count > wholeTopicAfter) {
                metrics.wholeTopic(topic);
            }
        });
        for (Topic topic : changedStatus) {
            if (topic.streamed()) {
                frames.put(LiveKey.status(topic), new Frame(version, started, topic, statusEvent(version, topic)));
            }
        }
        var views = new EnumMap<>(current.topics());
        for (Topic topic : changedTopics) {
            views.put(topic, view(topic, nowMillis));
        }
        changes.clear();
        changedTopics.clear();
        changedStatus.clear();
        waitingSince.forEach((topic, since) -> metrics.publishDelay(topic, started - since));
        waitingSince.clear();
        current = new LiveSnapshot(version, nowMillis, Collections.unmodifiableMap(views));
        publisher.publish(current, frames);
        metrics.publishTook(nanoClock.getAsLong() - started);
    }

    /** The topic's view, its snapshot event stamped with the publication time ({@code "t"}) for client-side lag. */
    private TopicView view(Topic topic, long nowMillis) {
        Health h = health.get(topic);
        var copy = Collections.unmodifiableMap(new TreeMap<>(items.get(topic)));
        if (!topic.streamed()) {
            return new TopicView(topic, copy, h.stale, h.updatedMillis, new byte[0]);
        }
        var out = new ByteArrayOutputStream(64 + copy.size() * 160);
        out.writeBytes(utf8("event: snapshot\ndata: {\"topic\":\"" + topic.wire() + "\",\"t\":" + nowMillis
                + ",\"stale\":" + h.stale
                + ",\"updated\":" + updated(h) + ",\"items\":["));
        boolean first = true;
        for (byte[] json : copy.values()) {
            if (!first) {
                out.write(',');
            }
            out.writeBytes(json);
            first = false;
        }
        out.writeBytes(utf8("]}\n\n"));
        return new TopicView(topic, copy, h.stale, h.updatedMillis, out.toByteArray());
    }

    /** {@code {"t":<ms>, ...item}}: the item's JSON with the publication time first, for client-side lag. */
    private static byte[] deltaEvent(long version, long nowMillis, LiveKey key, byte[] json) {
        byte[] body = json == REMOVED
                ? utf8("{\"id\":" + JSON.writeValueAsString(key.id()) + ",\"removed\":true}")
                : json;
        var out = new ByteArrayOutputStream(body.length + 64);
        out.writeBytes(utf8("id: " + version + "\nevent: " + key.topic().wire() + "\ndata: {\"t\":" + nowMillis));
        if (body.length > 2) { // "{}" has no fields to append
            out.write(',');
            out.write(body, 1, body.length - 1); // drop the item's own opening brace
        } else {
            out.write('}');
        }
        out.writeBytes(utf8("\n\n"));
        return out.toByteArray();
    }

    private byte[] statusEvent(long version, Topic topic) {
        Health h = health.get(topic);
        return utf8("id: " + version + "\nevent: status\ndata: {\"topic\":\"" + topic.wire() + "\",\"stale\":"
                + h.stale + ",\"updated\":" + updated(h) + "}\n\n");
    }

    private double feedAgeSeconds(Topic topic) {
        long updated = publisher.current().topic(topic).updatedMillis();
        return updated == 0 ? Double.NaN : (millisClock.getAsLong() - updated) / 1000.0;
    }

    private static String updated(Health h) {
        return h.updatedMillis == 0 ? "null" : Long.toString(h.updatedMillis);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
