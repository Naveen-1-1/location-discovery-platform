package io.github.naveen11.placemap.livehub.sse;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.livehub.state.Topic;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class LiveServletTest {

    @Test
    void readsTheTopicsParameter() {
        assertThat(LiveServlet.topics(null)).contains(EnumSet.allOf(Topic.class));
        assertThat(LiveServlet.topics("")).contains(EnumSet.allOf(Topic.class));
        assertThat(LiveServlet.topics("bikes")).contains(EnumSet.of(Topic.BIKES));
        assertThat(LiveServlet.topics(" bikes , bikes")).contains(EnumSet.of(Topic.BIKES));
        assertThat(LiveServlet.topics("bikes,trains")).isEmpty();
    }
}
