package io.github.naveen11.placemap.livehub.departures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.naveen11.placemap.concurrent.SnapshotPublisher;
import io.github.naveen11.placemap.livehub.state.Frame;
import io.github.naveen11.placemap.livehub.state.LiveKey;
import io.github.naveen11.placemap.livehub.state.LiveSnapshot;
import io.github.naveen11.placemap.livehub.state.Topic;
import io.github.naveen11.placemap.livehub.state.TopicView;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DeparturesControllerTest {

    private final AtomicLong millis = new AtomicLong(1_000_000);
    private final AtomicInteger polls = new AtomicInteger();
    private final MockMvc mvc;

    DeparturesControllerTest() {
        var items = Map.of("place-harsq", "{\"id\":\"place-harsq\",\"next\":[{\"route\":\"Red\",\"time\":5}]}"
                .getBytes(StandardCharsets.UTF_8));
        var snapshot = new LiveSnapshot(3, 1, Map.of(Topic.DEPARTURES,
                new TopicView(Topic.DEPARTURES, items, false, 900_000, new byte[0])));
        var publisher = new SnapshotPublisher<LiveSnapshot, LiveKey, Frame>(snapshot, LiveSnapshot::version);
        var pollNow = new PollNow();
        pollNow.connect(polls::incrementAndGet);
        mvc = MockMvcBuilders.standaloneSetup(new DeparturesController(publisher,
                new Demand(Duration.ofMinutes(2), millis::get), pollNow)).build();
    }

    @Test
    void answersFromMemoryAndWakesAnIdleFeedOnce() throws Exception {
        mvc.perform(get("/live/departures").param("stop", "place-harsq"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"stop":"place-harsq","stale":false,"updated":900000,
                         "departures":{"id":"place-harsq","next":[{"route":"Red","time":5}]}}""", JsonCompareMode.STRICT));
        mvc.perform(get("/live/departures").param("stop", "1")).andExpect(content().json("{\"departures\":null}"));
        assertThat(polls.get()).as("only the first request after a quiet spell").isEqualTo(1);

        millis.addAndGet(Duration.ofMinutes(3).toMillis());
        mvc.perform(get("/live/departures").param("stop", "1"));
        assertThat(polls.get()).isEqualTo(2);
    }

    @Test
    void refusesSomethingThatIsNotAStopId() throws Exception {
        mvc.perform(get("/live/departures").param("stop", "../../etc")).andExpect(status().isBadRequest());
    }
}
