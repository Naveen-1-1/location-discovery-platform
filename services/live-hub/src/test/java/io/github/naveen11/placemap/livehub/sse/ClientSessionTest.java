package io.github.naveen11.placemap.livehub.sse;

import static io.github.naveen11.placemap.livehub.sse.Fixtures.await;
import static io.github.naveen11.placemap.livehub.sse.Fixtures.frame;
import static io.github.naveen11.placemap.livehub.sse.Fixtures.key;
import static io.github.naveen11.placemap.livehub.sse.Fixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.livehub.LiveMetrics;
import io.github.naveen11.placemap.livehub.sse.Fixtures.RecordingSink;
import io.github.naveen11.placemap.livehub.state.Frame;
import io.github.naveen11.placemap.livehub.state.LiveKey;
import io.github.naveen11.placemap.livehub.state.LiveSnapshot;
import io.github.naveen11.placemap.livehub.state.Topic;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** One session in each connection mode, with a fake clock for the watchdog's decisions. */
@Timeout(20)
class ClientSessionTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long EVICT = 10 * SECOND;
    private static final long HEARTBEAT = 15 * SECOND;

    private final AtomicLong clock = new AtomicLong(1_000 * SECOND);
    private final AtomicReference<LiveSnapshot> current = new AtomicReference<>(snapshot(5, "\"x\""));
    private final RecordingSink sink = new RecordingSink();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicInteger cleanups = new AtomicInteger();
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private ClientSession session;

    @AfterEach
    void stop() {
        if (sink.gate != null) {
            sink.gate.countDown();
        }
        if (session != null) {
            session.close(ClientSession.End.SHUTDOWN);
        }
        pool.shutdownNow();
    }

    private ClientSession start(ConnectionMode mode, int mailboxCapacity) {
        session = new ClientSession("client", EnumSet.of(Topic.BIKES), mailboxCapacity, sink, current::get, 3000,
                clock::get, new LiveMetrics(registry), s -> cleanups.incrementAndGet());
        switch (mode) {
            case VIRTUAL -> Thread.ofVirtual().start(session::runLoop);
            case POOL -> session.startOn(pool);
        }
        return session;
    }

    @ParameterizedTest
    @EnumSource(ConnectionMode.class)
    void sendsRetryThenTheSnapshotThenOnlyNewerChanges(ConnectionMode mode) throws InterruptedException {
        start(mode, 8);
        await("the snapshot is written", () -> sink.text().contains("\"v\":5"));

        session.deliver(key("a"), frame(5, "a", 1)); // already in snapshot 5
        session.deliver(key("a"), frame(6, "a", 2));
        await("the newer change is written", () -> sink.text().contains("\"bikes\":2"));

        assertThat(sink.text()).isEqualTo("retry: 3000\n\n"
                + "id: 5\nevent: snapshot\ndata: {\"v\":5,\"items\":[\"x\"]}\n\n"
                + "id: 6\nevent: bikes\ndata: {\"id\":\"a\",\"bikes\":2}\n\n");
    }

    @ParameterizedTest
    @EnumSource(ConnectionMode.class)
    void closingEndsTheResponseOnceAndCleansUpOnce(ConnectionMode mode) throws InterruptedException {
        start(mode, 8);
        await("the snapshot is written", () -> sink.text().contains("\"v\":5"));

        var go = new CountDownLatch(1);
        Thread[] closers = new Thread[3];
        ClientSession.End[] reasons = {ClientSession.End.SLOW, ClientSession.End.CLIENT_GONE, ClientSession.End.SHUTDOWN};
        for (int i = 0; i < closers.length; i++) {
            ClientSession.End reason = reasons[i];
            closers[i] = Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                session.close(reason);
            });
        }
        go.countDown();
        for (Thread t : closers) {
            t.join();
        }
        await("the response is ended", () -> sink.completes.get() == 1);
        Thread.sleep(50);
        assertThat(sink.completes.get()).isEqualTo(1);
        assertThat(cleanups.get()).isEqualTo(1);
        assertThat(session.end()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(ConnectionMode.class)
    void aClientStuckInAWriteIsEvictedAndTheWriteInterrupted(ConnectionMode mode) throws InterruptedException {
        start(mode, 8);
        await("the snapshot is written", () -> sink.text().contains("\"v\":5"));
        sink.gate = new CountDownLatch(1); // the client stops reading
        session.deliver(key("a"), frame(6, "a", 2));
        await("the drain is stuck in a write", () -> sink.blockedWrites.get() == 1);

        clock.addAndGet(EVICT); // exactly 10 s: not yet
        session.check(EVICT, HEARTBEAT);
        assertThat(session.isClosed()).isFalse();

        clock.addAndGet(1);
        session.check(EVICT, HEARTBEAT);
        assertThat(session.end()).isEqualTo(ClientSession.End.SLOW);
        await("the interrupted drain ends the response", () -> sink.completes.get() == 1);
        assertThat(cleanups.get()).isEqualTo(1);
        // How far behind it was, recorded once even if the watchdog looks again.
        session.check(EVICT, HEARTBEAT);
        var behind = registry.get("placemap.live.evictions.behind").timer();
        assertThat(behind.count()).isEqualTo(1);
        assertThat(behind.max(TimeUnit.NANOSECONDS)).isEqualTo((double) EVICT + 1);
    }

    @Test
    void aSessionWaitingTooLongForAPoolThreadIsEvicted() {
        session = new ClientSession("client", EnumSet.of(Topic.BIKES), 8, sink, current::get, 3000, clock::get,
                new LiveMetrics(registry), s -> cleanups.incrementAndGet());
        session.startOn(task -> { }); // a saturated pool: the job never runs

        clock.addAndGet(EVICT + 1); // the initial RESYNC has waited longer than 10 s
        session.check(EVICT, HEARTBEAT);
        assertThat(session.end()).isEqualTo(ClientSession.End.SLOW);
    }

    @ParameterizedTest
    @EnumSource(ConnectionMode.class)
    void anOverflowWhileStuckIsRepairedWithAFreshSnapshot(ConnectionMode mode) throws InterruptedException {
        start(mode, 2);
        await("the snapshot is written", () -> sink.text().contains("\"v\":5"));
        sink.gate = new CountDownLatch(1);
        session.deliver(key("a"), frame(6, "a", 1));
        await("the drain is stuck in a write", () -> sink.blockedWrites.get() == 1);

        current.set(snapshot(7, "\"y\""));
        session.deliver(key("b"), frame(7, "b", 1));
        session.deliver(key("c"), frame(7, "c", 1));
        session.deliver(key("d"), frame(7, "d", 1)); // third key in a mailbox for two: overflow
        session.deliver(key("e"), frame(8, "e", 4)); // after the overflow: still delivered
        current.set(snapshot(8, "\"z\""));

        CountDownLatch gate = sink.gate;
        sink.gate = null;
        gate.countDown();
        await("the resync snapshot is written", () -> sink.text().contains("\"v\":8"));

        String text = sink.text();
        assertThat(text).contains("\"id\":\"a\"").doesNotContain("\"id\":\"b\"", "\"id\":\"c\"", "\"id\":\"d\"");
        assertThat(text).as("e is in snapshot 8, so it is not sent again").doesNotContain("\"id\":\"e\"");
        assertThat(registry.get("placemap.live.mailbox.overflows").counter().count()).isEqualTo(1);
        assertThat(registry.get("placemap.live.snapshots.sent").counter().count()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(ConnectionMode.class)
    void anIdleStreamGetsAHeartbeat(ConnectionMode mode) throws InterruptedException {
        start(mode, 8);
        await("the snapshot is written", () -> sink.text().contains("\"v\":5"));

        clock.addAndGet(HEARTBEAT); // exactly 15 s: not yet
        session.check(EVICT, HEARTBEAT);
        clock.addAndGet(1);
        session.check(EVICT, HEARTBEAT);
        await("the heartbeat is written", () -> sink.text().endsWith(": hb\n\n"));
        assertThat(sink.text().split(": hb", -1)).hasSize(2);
    }

    @Test
    void ignoresFramesWithoutATopicItAskedFor() throws InterruptedException {
        start(ConnectionMode.VIRTUAL, 8);
        await("the snapshot is written", () -> sink.text().contains("\"v\":5"));
        session.deliver(LiveKey.HEARTBEAT, Frame.HEARTBEAT); // only the watchdog sends heartbeats
        session.deliver(key("a"), frame(6, "a", 2));
        await("the change is written", () -> sink.text().contains("\"bikes\":2"));
        assertThat(sink.text()).doesNotContain(": hb");
    }
}
