package io.github.naveen11.placemap.livehub.sse;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.livehub.state.Topic;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class LiveServletTest {

    @Test
    void readsTheTopicsParameter() {
        var streamed = EnumSet.of(Topic.BIKES, Topic.ALERTS, Topic.VEHICLES);
        assertThat(LiveServlet.topics(null)).contains(streamed);
        assertThat(LiveServlet.topics("")).contains(streamed);
        assertThat(LiveServlet.topics("bikes")).contains(EnumSet.of(Topic.BIKES));
        assertThat(LiveServlet.topics(" bikes , alerts")).contains(EnumSet.of(Topic.BIKES, Topic.ALERTS));
        assertThat(LiveServlet.topics("bikes,trains")).isEmpty();
        assertThat(LiveServlet.topics("departures")).as("too big to stream; asked for per stop").isEmpty();
    }
}
