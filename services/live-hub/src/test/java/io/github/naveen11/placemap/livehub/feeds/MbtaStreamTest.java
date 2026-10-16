package io.github.naveen11.placemap.livehub.feeds;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.livehub.LiveMetrics;
import io.github.naveen11.placemap.livehub.state.FeedUpdate;
import io.github.naveen11.placemap.livehub.state.Topic;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.launchdarkly.eventsource.FaultEvent;
import com.launchdarkly.eventsource.MessageEvent;
import com.launchdarkly.eventsource.StreamIOException;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** MBTA's server-sent events, as the SSE client hands them over, turned into updates without a network. */
class MbtaStreamTest {

    private static final URI VEHICLES = URI.create("https://api-v3.mbta.com/vehicles");

    private final List<FeedUpdate> submitted = new ArrayList<>();
    private final MbtaStream stream = new MbtaStream(VEHICLES, "unused", Topic.VEHICLES, MbtaV3.vehicles(Area.ANYWHERE),
            submitted::add, new LiveMetrics(new SimpleMeterRegistry()), Duration.ofSeconds(15), Duration.ofMinutes(1),
            () -> 42L);

    private void receive(String event, String data) throws InterruptedException {
        stream.handle(new MessageEvent(event, data, null, VEHICLES));
    }

    @Test
    void aResetMakesTheStreamHealthyAndLaterEventsBecomeUpdates() throws InterruptedException {
        assertThat(stream.healthy()).isFalse();
        receive("reset", "[" + MbtaV3Test.BUS + "]");
        receive("update", MbtaV3Test.BUS);
        receive("remove", "{\"id\":\"y2040\",\"type\":\"vehicle\"}");
        receive("unknown", "{}");

        assertThat(stream.healthy()).isTrue();
        assertThat(submitted).hasSize(3);
        assertThat(submitted.get(0)).isInstanceOf(FeedUpdate.Replace.class);
        assertThat(submitted.get(1)).isInstanceOf(FeedUpdate.Upsert.class);
        assertThat(submitted.get(2)).isInstanceOf(FeedUpdate.Remove.class);
    }

    @Test
    void eachTickWhileConnectedCountsAsAnAnswer() throws InterruptedException {
        assertThat(stream.poll()).isEqualTo(Duration.ofSeconds(15));
        assertThat(submitted).as("not connected yet: no answer").isEmpty();

        receive("reset", "[]");
        stream.poll();
        assertThat(submitted.getLast()).isInstanceOf(FeedUpdate.Touch.class);
    }

    @Test
    void aBrokenStreamIsUnhealthyUntilTheNextReset() throws InterruptedException {
        receive("reset", "[]");
        stream.handle(new FaultEvent(new StreamIOException(new IOException("connection reset"))));
        assertThat(stream.healthy()).isFalse();
        stream.poll();
        assertThat(submitted).as("no tick while disconnected").singleElement().isInstanceOf(FeedUpdate.Replace.class);
    }

    @Test
    void anUnreadableEventIsSkipped() throws InterruptedException {
        receive("update", "{not json");
        assertThat(submitted).isEmpty();
    }
}
