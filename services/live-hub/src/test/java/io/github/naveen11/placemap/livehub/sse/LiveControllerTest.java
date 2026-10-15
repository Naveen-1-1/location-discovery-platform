package io.github.naveen11.placemap.livehub.sse;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.livehub.state.Topic;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class LiveControllerTest {

    @Test
    void readsTheTopicsParameter() {
        var streamed = EnumSet.of(Topic.BIKES, Topic.ALERTS, Topic.VEHICLES);
        assertThat(LiveController.topics(null)).contains(streamed);
        assertThat(LiveController.topics("")).contains(streamed);
        assertThat(LiveController.topics("bikes")).contains(EnumSet.of(Topic.BIKES));
        assertThat(LiveController.topics(" bikes , alerts")).contains(EnumSet.of(Topic.BIKES, Topic.ALERTS));
        assertThat(LiveController.topics("bikes,trains")).isEmpty();
        assertThat(LiveController.topics("departures")).as("too big to stream; asked for per stop").isEmpty();
    }
}
