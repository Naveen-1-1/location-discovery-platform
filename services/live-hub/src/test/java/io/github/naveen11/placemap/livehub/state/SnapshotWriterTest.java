package io.github.naveen11.placemap.livehub.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.livehub.LiveMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The single writer driven by hand: what it publishes for each kind of update, byte for byte. */
class SnapshotWriterTest {

    private final AtomicLong millis = new AtomicLong(1_000_000);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SnapshotWriter writer = new SnapshotWriter(16, Duration.ofMillis(10), Duration.ofMillis(250), 3,
            millis::get, System::nanoTime, new LiveMetrics(registry));
    private final List<String> delivered = new ArrayList<>();

    SnapshotWriterTest() {
        writer.publisher().subscribe((key, frame) -> delivered.add(text(frame.bytes())));
    }

    @Test
    void startsEmptyAndStaleAtVersionZero() {
        LiveSnapshot snapshot = writer.current();
        assertThat(snapshot.version()).isZero();
        TopicView bikes = snapshot.topic(Topic.BIKES);
        assertThat(bikes.stale()).isTrue();
        assertThat(text(bikes.snapshotEvent()))
                .isEqualTo("event: snapshot\ndata: {\"topic\":\"bikes\",\"stale\":true,\"updated\":null,\"items\":[]}\n\n");
    }

    @Test
    void theFirstAnswerSendsEveryItemAndATurnToFresh() {
        writer.apply(replace(Map.of("a", "{\"id\":\"a\",\"bikes\":3}", "b", "{\"id\":\"b\",\"bikes\":0}")));
        writer.tick();

        assertThat(writer.current().version()).isEqualTo(1);
        assertThat(delivered).containsExactlyInAnyOrder(
                "id: 1\nevent: bikes\ndata: {\"t\":1000000,\"id\":\"a\",\"bikes\":3}\n\n",
                "id: 1\nevent: bikes\ndata: {\"t\":1000000,\"id\":\"b\",\"bikes\":0}\n\n",
                "id: 1\nevent: status\ndata: {\"topic\":\"bikes\",\"stale\":false,\"updated\":1000000}\n\n");
        assertThat(text(writer.current().topic(Topic.BIKES).snapshotEvent())).isEqualTo(
                "event: snapshot\ndata: {\"topic\":\"bikes\",\"stale\":false,\"updated\":1000000,\"items\":["
                        + "{\"id\":\"a\",\"bikes\":3},{\"id\":\"b\",\"bikes\":0}]}\n\n");
    }

    @Test
    void laterAnswersSendOnlyWhatChangedOrDisappeared() {
        writer.apply(replace(Map.of("a", "{\"id\":\"a\",\"bikes\":3}", "b", "{\"id\":\"b\",\"bikes\":0}")));
        writer.tick();
        delivered.clear();

        millis.addAndGet(60_000);
        writer.apply(replace(Map.of("a", "{\"id\":\"a\",\"bikes\":2}", "c", "{\"id\":\"c\",\"bikes\":9}")));
        writer.tick();

        assertThat(delivered).containsExactlyInAnyOrder(
                "id: 2\nevent: bikes\ndata: {\"t\":1060000,\"id\":\"a\",\"bikes\":2}\n\n",
                "id: 2\nevent: bikes\ndata: {\"t\":1060000,\"id\":\"b\",\"removed\":true}\n\n",
                "id: 2\nevent: bikes\ndata: {\"t\":1060000,\"id\":\"c\",\"bikes\":9}\n\n");
        assertThat(writer.current().topic(Topic.BIKES).items()).containsOnlyKeys("a", "c");
    }

    @Test
    void anUnchangedAnswerSendsNothingButMovesUpdated() {
        writer.apply(replace(Map.of("a", "{\"id\":\"a\",\"bikes\":3}")));
        writer.tick();
        delivered.clear();

        millis.addAndGet(60_000);
        writer.apply(replace(Map.of("a", "{\"id\":\"a\",\"bikes\":3}")));
        writer.tick();

        assertThat(delivered).isEmpty();
        assertThat(writer.current().version()).isEqualTo(2);
        assertThat(writer.current().topic(Topic.BIKES).updatedMillis()).isEqualTo(1_060_000);
    }

    @Test
    void aQuietTickPublishesNothing() {
        writer.apply(replace(Map.of("a", "{\"id\":\"a\",\"bikes\":3}")));
        writer.tick();
        writer.tick();
        writer.tick();
        assertThat(writer.current().version()).isEqualTo(1);
    }

    @Test
    void aSilentFeedTurnsStaleAfterThreeTtlsAndFreshAgainWhenItAnswers() {
        writer.apply(replace(Map.of("a", "{\"id\":\"a\",\"bikes\":3}")));
        writer.tick();
        delivered.clear();

        writer.apply(new FeedUpdate.Failed(Topic.BIKES, "timeout", millis.get()));
        millis.addAndGet(180_000); // exactly 3 × 60 s: not yet
        writer.tick();
        assertThat(delivered).isEmpty();

        millis.addAndGet(1);
        writer.tick();
        assertThat(delivered).containsExactly(
                "id: 2\nevent: status\ndata: {\"topic\":\"bikes\",\"stale\":true,\"updated\":1000000}\n\n");
        assertThat(writer.current().topic(Topic.BIKES).stale()).isTrue();
        assertThat(registry.get("placemap.live.feed.stale").tag("topic", "bikes").gauge().value()).isEqualTo(1);

        delivered.clear();
        writer.apply(replace(Map.of("a", "{\"id\":\"a\",\"bikes\":3}")));
        writer.tick();
        assertThat(delivered).containsExactly(
                "id: 3\nevent: status\ndata: {\"topic\":\"bikes\",\"stale\":false,\"updated\":1180001}\n\n");
    }

    @Test
    void theFeedsTtlSetsWhenItCountsAsStale() {
        writer.apply(new FeedUpdate.Replace(Topic.BIKES, Map.of(), millis.get(), Duration.ofSeconds(10)));
        writer.tick();
        millis.addAndGet(30_001);
        writer.tick();
        assertThat(writer.current().topic(Topic.BIKES).stale()).isTrue();
    }

    @Test
    void submitDropsAndCountsWhenTheQueueStaysFull() throws InterruptedException {
        for (int i = 0; i < 16; i++) {
            assertThat(writer.submit(replace(Map.of()))).isTrue();
        }
        assertThat(writer.submit(replace(Map.of()))).isFalse();
        assertThat(registry.get("placemap.live.updates.dropped").tag("topic", "bikes").counter().count()).isEqualTo(1);
    }

    @Test
    void aTopicThatIsNotStreamedIsKeptButNeverSentToClients() {
        writer.apply(new FeedUpdate.Replace(Topic.DEPARTURES,
                Map.of("place-harsq", "{\"id\":\"place-harsq\",\"next\":[]}".getBytes(StandardCharsets.UTF_8)),
                millis.get(), Duration.ofSeconds(15)));
        writer.tick();
        assertThat(delivered).isEmpty();
        assertThat(writer.current().topic(Topic.DEPARTURES).items()).containsOnlyKeys("place-harsq");
        assertThat(writer.current().topic(Topic.DEPARTURES).stale()).isFalse();
        assertThat(writer.current().topic(Topic.DEPARTURES).snapshotEvent()).isEmpty();
    }

    @Test
    void streamEventsChangeOneItemAtATimeAndAQuietStreamStaysFresh() {
        Duration ttl = Duration.ofSeconds(15);
        writer.apply(new FeedUpdate.Upsert(Topic.VEHICLES, "y1", "{\"id\":\"y1\",\"lat\":1}".getBytes(StandardCharsets.UTF_8), millis.get(), ttl));
        writer.tick();
        assertThat(delivered).containsExactlyInAnyOrder(
                "id: 1\nevent: vehicles\ndata: {\"t\":1000000,\"id\":\"y1\",\"lat\":1}\n\n",
                "id: 1\nevent: status\ndata: {\"topic\":\"vehicles\",\"stale\":false,\"updated\":1000000}\n\n");
        delivered.clear();

        writer.apply(new FeedUpdate.Remove(Topic.VEHICLES, "y1", millis.get(), ttl));
        writer.apply(new FeedUpdate.Remove(Topic.VEHICLES, "never-seen", millis.get(), ttl));
        writer.tick();
        assertThat(delivered).containsExactly("id: 2\nevent: vehicles\ndata: {\"t\":1000000,\"id\":\"y1\",\"removed\":true}\n\n");
        delivered.clear();

        for (int i = 0; i < 4; i++) { // a minute of touches every 15 s: never stale
            millis.addAndGet(15_000);
            writer.apply(new FeedUpdate.Touch(Topic.VEHICLES, millis.get(), ttl));
            writer.tick();
        }
        assertThat(delivered).isEmpty();
        assertThat(writer.current().topic(Topic.VEHICLES).stale()).isFalse();
        millis.addAndGet(45_001);
        writer.tick();
        assertThat(writer.current().topic(Topic.VEHICLES).stale()).as("3 × 15 s without a touch").isTrue();
    }

    private FeedUpdate.Replace replace(Map<String, String> items) {
        var bytes = new LinkedHashMap<String, byte[]>();
        items.forEach((id, json) -> bytes.put(id, json.getBytes(StandardCharsets.UTF_8)));
        return new FeedUpdate.Replace(Topic.BIKES, bytes, millis.get(), Duration.ofSeconds(60));
    }

    static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
