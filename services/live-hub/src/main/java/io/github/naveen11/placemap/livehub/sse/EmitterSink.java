package io.github.naveen11.placemap.livehub.sse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * A session's events into an {@link SseEmitter}. Frames arrive already formatted as server-sent events (serialized
 * once for every client), so they go out as raw bytes.
 *
 * <p>Spring's converters flush after every item they write, so a batch's small frames are joined into one item and
 * go out with one flush, as one batch should. A large frame (a snapshot) goes out on its own instead of being copied.
 * Nothing is kept between batches, so an idle stream holds no buffer.
 *
 * <p>Writes are blocking servlet I/O from the session's drain thread: a client that stops reading eventually blocks
 * them until the watchdog evicts it, or Tomcat's write timeout fires as a backstop.
 */
final class EmitterSink implements EventSink {

    private static final MediaType RAW = MediaType.TEXT_EVENT_STREAM;
    private static final int ALONE = 8 * 1024;

    private final SseEmitter emitter;
    private final List<byte[]> pending = new ArrayList<>(); // drain thread only
    private int pendingBytes;

    EmitterSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        if (bytes.length >= ALONE) {
            sendPending();
            send(bytes);
        } else {
            pending.add(bytes);
            pendingBytes += bytes.length;
        }
    }

    @Override
    public void flush() throws IOException {
        sendPending();
    }

    @Override
    public void complete() {
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            // Already completed (the client went away).
        }
    }

    private void sendPending() throws IOException {
        if (pending.isEmpty()) {
            return;
        }
        byte[] joined = new byte[pendingBytes];
        int at = 0;
        for (byte[] frame : pending) {
            System.arraycopy(frame, 0, joined, at, frame.length);
            at += frame.length;
        }
        pending.clear();
        pendingBytes = 0;
        send(joined);
    }

    /** One item: ResponseBodyEmitter's raw send, which SseEmitter does not wrap in "data:" lines. */
    private void send(byte[] bytes) throws IOException {
        emitter.send(Set.of(new DataWithMediaType(bytes, RAW)));
    }
}
