package io.github.naveen11.placemap.livehub.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.naveen11.placemap.livehub.LiveMetrics;
import io.github.naveen11.placemap.livehub.feeds.BluebikesFeed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * A sequence of real-shaped GBFS answers through the feed and the writer (plan, Phase 4 "GBFS sequences"): what a
 * subscribed client receives after each poll, including an outage long enough to go stale and the recovery.
 */
class GbfsSequenceTest {

    private static final String URL = "https://gbfs.bluebikes.com/gbfs/en/station_status.json";

    private final AtomicLong millis = new AtomicLong(1_000_000);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final LiveMetrics metrics = new LiveMetrics(new SimpleMeterRegistry());
    private final SnapshotWriter writer = new SnapshotWriter(16, Duration.ofMillis(10), Duration.ofMillis(250), 3,
            millis::get, System::nanoTime, metrics);
    private final BluebikesFeed feed = new BluebikesFeed(builder.build(), URL, Duration.ofSeconds(15),
            Duration.ofMinutes(5), update -> {
                writer.apply(update); // as the writer thread would, on taking it from the queue
                return true;
            }, metrics, millis::get);
    private final List<String> events = new ArrayList<>();

    GbfsSequenceTest() {
        writer.publisher().subscribe((key, frame) -> events.add(event(frame)));
    }

    @Test
    void clientsSeeEachChangeOnceThenStaleThenFreshAgain() throws InterruptedException {
        respond(body(station("a", 3, 9), station("b", 0, 12)));
        respond(body(station("a", 3, 9), station("b", 0, 12)));            // nothing changed
        respond(body(station("a", 2, 10), station("b", 0, 12)));           // someone took a bike from a
        respond(body(station("a", 2, 10)));                                // b was removed from the system
        for (int i = 0; i < 4; i++) {
            server.expect(requestTo(URL)).andRespond(withServerError());   // a four-minute outage
        }
        respond(body(station("a", 5, 7)));                                 // back

        pollAndTick();
        assertThat(events).containsExactlyInAnyOrder("status stale=false", "bikes a=3", "bikes b=0");
        pollAndTick();
        assertThat(events).isEmpty();
        pollAndTick();
        assertThat(events).containsExactly("bikes a=2");
        pollAndTick();
        assertThat(events).containsExactly("bikes b removed");
        for (int minute = 1; minute <= 3; minute++) {
            pollAndTick();
            assertThat(events).as("%d min without an answer: not yet 3 × the 60 s ttl", minute).isEmpty();
        }
        pollAndTick();
        assertThat(events).containsExactly("status stale=true");
        pollAndTick();
        assertThat(events).containsExactlyInAnyOrder("status stale=false", "bikes a=5");
        server.verify();
    }

    private void pollAndTick() throws InterruptedException {
        events.clear();
        millis.addAndGet(60_000);
        feed.poll();
        writer.tick();
    }

    private void respond(String body) {
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static String body(String... stations) {
        return "{\"last_updated\":1791744266,\"ttl\":60,\"version\":\"1.1\",\"data\":{\"stations\":["
                + String.join(",", stations) + "]}}";
    }

    private static String station(String id, int bikes, int docks) {
        return "{\"station_id\":\"" + id + "\",\"num_bikes_available\":" + bikes + ",\"num_docks_available\":" + docks
                + ",\"is_installed\":1,\"is_renting\":1,\"is_returning\":1,\"num_ebikes_available\":0}";
    }

    /** A short readable form of an SSE event, e.g. "bikes a=3". */
    private static String event(Frame frame) {
        String text = SnapshotWriterTest.text(frame.bytes());
        String name = text.split("\nevent: ")[1].split("\n")[0];
        String data = text.split("\ndata: ")[1].strip();
        if (name.equals("status")) {
            return "status stale=" + data.split("\"stale\":")[1].split(",")[0];
        }
        String id = data.split("\"id\":\"")[1].split("\"")[0];
        return data.contains("\"removed\":true") ? name + " " + id + " removed"
                : name + " " + id + "=" + data.split("\"bikes\":")[1].split(",")[0];
    }
}
