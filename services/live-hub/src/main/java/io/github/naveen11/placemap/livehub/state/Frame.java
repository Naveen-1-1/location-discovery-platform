package io.github.naveen11.placemap.livehub.state;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * One server-sent event, serialized once by the snapshot writer and shared by every client that gets it.
 * Frames are compared by identity: {@link #RESYNC} and {@link #HEARTBEAT} are markers, not data.
 */
public final class Frame {

    /** "Send this client the whole current snapshot": a new connection's first frame, and what an overflow leaves. */
    public static final Frame RESYNC = new Frame(-1, 0, new byte[0]);

    /** An SSE comment that keeps proxies and the browser from timing the stream out. */
    public static final Frame HEARTBEAT = new Frame(-1, 0, ": hb\n\n".getBytes(StandardCharsets.US_ASCII));

    private final long version;
    private final long publishedNanos;
    private final Topic topic;
    private final boolean whole;
    private final byte[] bytes;

    public Frame(long version, long publishedNanos, byte[] bytes) {
        this(version, publishedNanos, null, false, bytes);
    }

    public Frame(long version, long publishedNanos, Topic topic, byte[] bytes) {
        this(version, publishedNanos, topic, false, bytes);
    }

    private Frame(long version, long publishedNanos, Topic topic, boolean whole, byte[] bytes) {
        this.version = version;
        this.publishedNanos = publishedNanos;
        this.topic = topic;
        this.whole = whole;
        this.bytes = bytes;
    }

    /**
     * "Send this client the topic's current snapshot": what a publish that changed many of one topic's items sends in
     * their place, so a poll that moves hundreds of vehicles takes one mailbox slot, not hundreds.
     */
    public static Frame wholeTopic(long version, long publishedNanos, Topic topic) {
        return new Frame(version, publishedNanos, Objects.requireNonNull(topic, "topic"), true, new byte[0]);
    }

    /** The snapshot version this change belongs to; a client that loaded that snapshot or a newer one skips it. */
    public long version() {
        return version;
    }

    /** {@link System#nanoTime()} when the snapshot was published, for the fan-out lag metric. */
    public long publishedNanos() {
        return publishedNanos;
    }

    /** The topic the frame is about; null for the markers and for frames made without one. */
    public Topic topic() {
        return topic;
    }

    /** Whether this stands for the topic's whole current snapshot rather than carrying an event. */
    public boolean whole() {
        return whole;
    }

    /** The complete event, ready to write; empty for the markers and whole-topic frames. Never modify. */
    public byte[] bytes() {
        return bytes;
    }
}
