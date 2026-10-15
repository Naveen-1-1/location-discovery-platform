package io.github.naveen11.placemap.livehub.sse;

import io.github.naveen11.placemap.livehub.state.Frame;
import io.github.naveen11.placemap.livehub.state.LiveKey;
import io.github.naveen11.placemap.livehub.state.LiveSnapshot;
import io.github.naveen11.placemap.livehub.state.Topic;
import io.github.naveen11.placemap.livehub.state.TopicView;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.awaitility.Awaitility;

/** Hand-made snapshots and frames, and a sink that records what a client would receive. */
final class Fixtures {

    private Fixtures() {
    }

    static LiveSnapshot snapshot(long version, String items) {
        byte[] event = ("event: snapshot\ndata: {\"v\":" + version + ",\"items\":[" + items + "]}\n\n")
                .getBytes(StandardCharsets.UTF_8);
        var view = new TopicView(Topic.BIKES, Map.of(), false, 1, event);
        return new LiveSnapshot(version, 1, Map.of(Topic.BIKES, view));
    }

    static Frame frame(long version, String id, int bikes) {
        return new Frame(version, 0, ("id: " + version + "\nevent: bikes\ndata: {\"id\":\"" + id
                + "\",\"bikes\":" + bikes + "}\n\n").getBytes(StandardCharsets.UTF_8));
    }

    static LiveKey key(String id) {
        return LiveKey.item(Topic.BIKES, id);
    }

    static void await(String what, BooleanSupplier condition) {
        Awaitility.await(what).atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(2))
                .until(condition::getAsBoolean);
    }

    /** Records writes. While {@link #gate} is set, each write waits for it: a client that stopped reading. */
    static final class RecordingSink implements EventSink {
        private final StringBuilder out = new StringBuilder();
        final AtomicInteger completes = new AtomicInteger();
        final AtomicInteger blockedWrites = new AtomicInteger();
        volatile CountDownLatch gate;

        @Override
        public void write(byte[] bytes) throws IOException {
            CountDownLatch g = gate;
            if (g != null) {
                blockedWrites.incrementAndGet();
                try {
                    g.await();
                } catch (InterruptedException e) {
                    throw new InterruptedIOException("write interrupted"); // what a socket write does
                }
            }
            synchronized (this) {
                out.append(new String(bytes, StandardCharsets.UTF_8));
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void complete() {
            completes.incrementAndGet();
        }

        synchronized String text() {
            return out.toString();
        }
    }
}
