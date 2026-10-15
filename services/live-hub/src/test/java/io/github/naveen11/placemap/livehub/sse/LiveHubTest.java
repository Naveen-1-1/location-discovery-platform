package io.github.naveen11.placemap.livehub.sse;

import static io.github.naveen11.placemap.livehub.sse.Fixtures.await;
import static io.github.naveen11.placemap.livehub.sse.Fixtures.frame;
import static io.github.naveen11.placemap.livehub.sse.Fixtures.key;
import static io.github.naveen11.placemap.livehub.sse.Fixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naveen11.placemap.concurrent.SnapshotPublisher;
import io.github.naveen11.placemap.livehub.LiveMetrics;
import io.github.naveen11.placemap.livehub.sse.Fixtures.RecordingSink;
import io.github.naveen11.placemap.livehub.state.Frame;
import io.github.naveen11.placemap.livehub.state.LiveKey;
import io.github.naveen11.placemap.livehub.state.LiveSnapshot;
import io.github.naveen11.placemap.livehub.state.Topic;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Admission limits, fan-out through the publisher, and shutdown. */
@Timeout(20)
class LiveHubTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SnapshotPublisher<LiveSnapshot, LiveKey, Frame> publisher =
            new SnapshotPublisher<>(snapshot(1, ""), LiveSnapshot::version);
    private final ThreadPoolTaskScheduler watchdog = new ThreadPoolTaskScheduler();
    // A watchdog tick of an hour: tests call checkAll() themselves.
    private final LiveHub hub = new LiveHub(publisher, new LiveHub.Settings(ConnectionMode.VIRTUAL, 8, 3, 2,
            Duration.ofSeconds(10), Duration.ofSeconds(15), Duration.ofHours(1), Duration.ofSeconds(2),
            Duration.ofSeconds(7)), System::nanoTime, new LiveMetrics(registry),
            new VirtualThreadTaskExecutor("live-conn-"), started(watchdog));

    private static ThreadPoolTaskScheduler started(ThreadPoolTaskScheduler scheduler) {
        scheduler.initialize();
        return scheduler;
    }

    @AfterEach
    void stop() {
        hub.close();
        watchdog.shutdown();
    }

    @Test
    void limitsStreamsPerClientAndInTotal() throws Exception {
        ClientSession first = hub.open("alice", EnumSet.of(Topic.BIKES), new RecordingSink());
        hub.open("alice", EnumSet.of(Topic.BIKES), new RecordingSink());
        assertThatThrownBy(() -> hub.open("alice", EnumSet.of(Topic.BIKES), new RecordingSink()))
                .isInstanceOfSatisfying(LiveHub.RefusedException.class,
                        e -> assertThat(e.refusal()).isEqualTo(LiveHub.Refusal.CLIENT_FULL));
        hub.open("bob", EnumSet.of(Topic.BIKES), new RecordingSink());
        assertThatThrownBy(() -> hub.open("carol", EnumSet.of(Topic.BIKES), new RecordingSink()))
                .isInstanceOfSatisfying(LiveHub.RefusedException.class,
                        e -> assertThat(e.refusal()).isEqualTo(LiveHub.Refusal.HUB_FULL));
        assertThat(hub.openSessions()).isEqualTo(3);

        first.close(ClientSession.End.CLIENT_GONE);
        hub.open("alice", EnumSet.of(Topic.BIKES), new RecordingSink()); // both limits freed a slot
        assertThat(hub.openSessions()).isEqualTo(3);
        assertThat(registry.get("placemap.live.connections").tag("result", "client_full").counter().count()).isEqualTo(1);
        assertThat(registry.get("placemap.live.connections").tag("result", "hub_full").counter().count()).isEqualTo(1);
        assertThat(registry.get("placemap.live.sessions.closed").tag("reason", "client_gone").counter().count())
                .isEqualTo(1);
    }

    @Test
    void everyOpenSessionGetsPublishedChangesUntilItCloses() throws Exception {
        var one = new RecordingSink();
        var two = new RecordingSink();
        ClientSession first = hub.open("alice", EnumSet.of(Topic.BIKES), one);
        hub.open("bob", EnumSet.of(Topic.BIKES), two);
        await("both have the snapshot", () -> one.text().contains("\"v\":1") && two.text().contains("\"v\":1"));

        publisher.publish(snapshot(2, ""), Map.of(key("a"), frame(2, "a", 3)));
        await("both have the change", () -> one.text().contains("\"bikes\":3") && two.text().contains("\"bikes\":3"));

        first.close(ClientSession.End.CLIENT_GONE);
        assertThat(publisher.subscriberCount()).isEqualTo(1);
    }

    @Test
    void shutdownClosesEverySessionAndRefusesNewOnes() throws Exception {
        var sink = new RecordingSink();
        ClientSession session = hub.open("alice", EnumSet.of(Topic.BIKES), sink);
        hub.close();
        assertThat(session.end()).isEqualTo(ClientSession.End.SHUTDOWN);
        await("the response is ended", () -> sink.completes.get() == 1);
        assertThatThrownBy(() -> hub.open("bob", EnumSet.of(Topic.BIKES), new RecordingSink()))
                .isInstanceOfSatisfying(LiveHub.RefusedException.class,
                        e -> assertThat(e.refusal()).isEqualTo(LiveHub.Refusal.CLOSING));
        assertThat(hub.openSessions()).isZero();
    }
}
