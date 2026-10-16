package io.github.naveen11.placemap.livehub.feeds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.naveen11.placemap.livehub.LiveMetrics;
import io.github.naveen11.placemap.livehub.state.FeedUpdate;
import io.github.naveen11.placemap.livehub.state.Topic;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Polling one realtime file: gzip, ETags, failures, and polling only while wanted. */
class MbtaRealtimeFeedTest {

    private static final String URL = "https://cdn.mbta.com/realtime/VehiclePositions_enhanced.json";
    private static final String BODY = """
            {"entity":[{"id":"1814","vehicle":{"position":{"latitude":42.1,"longitude":-71.2},
              "trip":{"route_id":"CR-Franklin"},"vehicle":{"id":"1814"},"timestamp":1}}]}""";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final List<FeedUpdate> submitted = new ArrayList<>();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicBoolean wanted = new AtomicBoolean(true);
    private final MbtaRealtimeFeed feed = new MbtaRealtimeFeed(builder.build(), URL, Topic.VEHICLES,
            Duration.ofSeconds(15), MbtaParsers.vehicles(Area.ANYWHERE), wanted::get, submitted::add,
            new LiveMetrics(registry), () -> 1_000L);

    @Test
    void readsAGzippedAnswerThenReusesItOnNotModified() throws Exception {
        var headers = new HttpHeaders();
        headers.setETag("\"v1\"");
        headers.set("Content-Encoding", "gzip");
        server.expect(requestTo(URL)).andExpect(header("Accept-Encoding", "gzip")).andExpect(headerDoesNotExist("If-None-Match"))
                .andRespond(withSuccess(gzip(BODY), MediaType.APPLICATION_JSON).headers(headers));
        server.expect(requestTo(URL)).andExpect(header("If-None-Match", "\"v1\""))
                .andRespond(withStatus(HttpStatus.NOT_MODIFIED));

        assertThat(feed.poll()).isEqualTo(Duration.ofSeconds(15));
        assertThat(feed.poll()).isEqualTo(Duration.ofSeconds(15));

        assertThat(submitted).hasSize(2).allSatisfy(u -> assertThat(u).isInstanceOfSatisfying(FeedUpdate.Replace.class,
                r -> assertThat(r.items()).containsOnlyKeys("1814")));
        server.verify();
    }

    @Test
    void aFailureIsReported() throws Exception {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        feed.poll();
        assertThat(submitted).singleElement().isInstanceOf(FeedUpdate.Failed.class);
        assertThat(registry.get("placemap.live.feed.polls").tag("outcome", "failed").counter().count()).isEqualTo(1);
    }

    @Test
    void anUnwantedFeedMakesNoRequest() throws Exception {
        wanted.set(false);
        feed.poll();
        assertThat(submitted).isEmpty();
        server.verify(); // no request was expected, and none was made
        assertThat(registry.get("placemap.live.feed.polls").tag("outcome", "not_wanted").counter().count()).isEqualTo(1);
    }

    private static byte[] gzip(String text) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(out)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }
}
