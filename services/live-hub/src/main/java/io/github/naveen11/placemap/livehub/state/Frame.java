package io.github.naveen11.placemap.livehub.state;

import java.nio.charset.StandardCharsets;

/**
 * One server-sent event, serialized once by the snapshot writer and shared by every client that gets it (ADR 0023).
 * Frames are compared by identity: {@link #RESYNC} and {@link #HEARTBEAT} are markers, not data.
 */
public final class Frame {

    /** "Send this client the whole current snapshot": a new connection's first frame, and what an overflow leaves. */
    public static final Frame RESYNC = new Frame(-1, 0, new byte[0]);

    /** An SSE comment that keeps proxies and the browser from timing the stream out (ADR 0018). */
    public static final Frame HEARTBEAT = new Frame(-1, 0, ": hb\n\n".getBytes(StandardCharsets.US_ASCII));

    private final long version;
    private final long publishedNanos;
    private final byte[] bytes;

    public Frame(long version, long publishedNanos, byte[] bytes) {
        this.version = version;
        this.publishedNanos = publishedNanos;
        this.bytes = bytes;
    }

    /** The snapshot version this change belongs to; a client that loaded that snapshot or a newer one skips it. */
    public long version() {
        return version;
    }

    /** {@link System#nanoTime()} when the snapshot was published, for the fan-out lag metric. */
    public long publishedNanos() {
        return publishedNanos;
    }

    /** The complete event, ready to write. Never modify. */
    public byte[] bytes() {
        return bytes;
    }
}
