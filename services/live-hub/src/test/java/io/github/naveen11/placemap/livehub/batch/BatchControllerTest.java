package io.github.naveen11.placemap.livehub.batch;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.naveen11.placemap.concurrent.SnapshotPublisher;
import io.github.naveen11.placemap.livehub.LiveProperties;
import io.github.naveen11.placemap.livehub.state.Frame;
import io.github.naveen11.placemap.livehub.state.LiveKey;
import io.github.naveen11.placemap.livehub.state.LiveSnapshot;
import io.github.naveen11.placemap.livehub.state.Topic;
import io.github.naveen11.placemap.livehub.state.TopicView;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BatchControllerTest {

    private final MockMvc mvc;

    BatchControllerTest() {
        var items = new TreeMap<String, byte[]>();
        items.put("a", "{\"id\":\"a\",\"bikes\":3}".getBytes(StandardCharsets.UTF_8));
        items.put("b", "{\"id\":\"b\",\"bikes\":0}".getBytes(StandardCharsets.UTF_8));
        var snapshot = new LiveSnapshot(7, 1, Map.of(Topic.BIKES, new TopicView(Topic.BIKES, items, false, 1_700, new byte[0])));
        var publisher = new SnapshotPublisher<LiveSnapshot, LiveKey, Frame>(snapshot, LiveSnapshot::version);
        var properties = new LiveProperties(Duration.ofMillis(250), 10, Duration.ofSeconds(1), 3, null, 1, 8, 10, 2,
                Duration.ofSeconds(10), Duration.ofSeconds(15), Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofSeconds(7), 4, null);
        mvc = MockMvcBuilders.standaloneSetup(new BatchController(publisher, properties)).build();
    }

    @Test
    void answersTheKnownItemsWithTheirTopicsHealth() throws Exception {
        mvc.perform(post("/internal/live/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"bikes:a\",\"bikes:missing\",\"trains:x\",\"nocolon\"]}"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"version":7,"topics":{"bikes":{"stale":false,"updated":1700}},
                         "items":{"bikes:a":{"id":"a","bikes":3}}}""", org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @Test
    void refusesTooManyKeys() throws Exception {
        mvc.perform(post("/internal/live/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"bikes:a\",\"bikes:b\",\"bikes:c\",\"bikes:d\",\"bikes:e\"]}"))
                .andExpect(status().isBadRequest());
    }
}
