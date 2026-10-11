package io.github.naveen11.placemap.livehub.feeds;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.livehub.LiveMetrics;
import io.github.naveen11.placemap.livehub.state.FeedUpdate;
import io.github.naveen11.placemap.livehub.state.Topic;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Reading MBTA's server-sent events, as they arrive on the wire, without a network. */
class MbtaStreamTest {

    private final List<FeedUpdate> submitted = new ArrayList<>();
    private final MbtaStream stream = new MbtaStream(HttpClient.newHttpClient(), URI.create("https://api-v3.mbta.com/vehicles"),
            "unused", Topic.VEHICLES, MbtaV3.vehicles(), submitted::add, new LiveMetrics(new SimpleMeterRegistry()),
            Duration.ofSeconds(15), Duration.ofMinutes(1), () -> 42L);

    @Test
    void aResetMakesTheStreamHealthyAndLaterEventsBecomeUpdates() throws InterruptedException {
        assertThat(stream.healthy()).isFalse();
        stream.consume(List.of(
                "event: reset", "data: [" + MbtaV3Test.BUS.replace("\n", "") + "]", "",
                ": a comment", "",
                "event: update", "data: " + MbtaV3Test.BUS.replace("\n", ""), "",
                "event: remove", "data: {\"id\":\"y2040\",\"type\":\"vehicle\"}", "",
                "event: unknown", "data: {}", "").iterator());

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

        stream.consume(List.of("event: reset", "data: []", "").iterator());
        stream.poll();
        assertThat(submitted.getLast()).isInstanceOf(FeedUpdate.Touch.class);
    }

    @Test
    void dataSplitOverSeveralLinesIsJoined() throws InterruptedException {
        stream.consume(List.of("event: reset", "data: [", "data: ]", "").iterator());
        assertThat(submitted).singleElement().isInstanceOf(FeedUpdate.Replace.class);
    }
}
