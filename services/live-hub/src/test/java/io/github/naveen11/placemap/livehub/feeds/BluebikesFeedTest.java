package io.github.naveen11.placemap.livehub.feeds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.naveen11.placemap.livehub.LiveMetrics;
import io.github.naveen11.placemap.livehub.state.FeedUpdate;
import io.github.naveen11.placemap.livehub.state.Topic;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Reading Bluebikes station_status, with the shape of the real feed on 2026-10-11 (GBFS 1.1, flags as 0/1). */
class BluebikesFeedTest {

    static final String URL = "https://gbfs.bluebikes.com/gbfs/en/station_status.json";

    static String body(int ttl, String... stations) {
        return "{\"last_updated\":1791744266,\"ttl\":" + ttl + ",\"version\":\"1.1\",\"data\":{\"stations\":["
                + String.join(",", stations) + "]}}";
    }

    static String station(String id, int bikes, int ebikes, int docks, int renting) {
        return "{\"station_id\":\"" + id + "\",\"num_bikes_available\":" + bikes + ",\"num_bikes_disabled\":0,"
                + "\"num_docks_available\":" + docks + ",\"num_docks_disabled\":1,\"is_installed\":1,"
                + "\"is_renting\":" + renting + ",\"is_returning\":1,\"last_reported\":1791744200,\"legacy_id\":\"67\","
                + "\"num_ebikes_available\":" + ebikes + ",\"eightd_has_available_keys\":false}";
    }

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final List<FeedUpdate> submitted = new ArrayList<>();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BluebikesFeed feed = new BluebikesFeed(builder.build(), URL, Duration.ofSeconds(15),
            Duration.ofMinutes(5), submitted::add, new LiveMetrics(registry), () -> 42L);

    @Test
    void turnsEachDockIntoTheCountsRidersCareAbout() {
        FeedUpdate.Replace update = BluebikesFeed.parse(body(60, station("f83493c0", 7, 2, 23, 1)), 42);

        assertThat(update.topic()).isEqualTo(Topic.BIKES);
        assertThat(update.ttl()).isEqualTo(Duration.ofSeconds(60));
        assertThat(update.fetchedAtMillis()).isEqualTo(42);
        assertThat(new String(update.items().get("f83493c0"), StandardCharsets.UTF_8)).isEqualTo(
                "{\"id\":\"f83493c0\",\"bikes\":7,\"ebikes\":2,\"docks\":23,\"installed\":true,\"renting\":true,"
                        + "\"returning\":true}");
    }

    @Test
    void acceptsBooleanFlagsFromNewerGbfsVersions() {
        String newer = "{\"ttl\":30,\"data\":{\"stations\":[{\"station_id\":\"x\",\"num_bikes_available\":1,"
                + "\"num_docks_available\":2,\"is_installed\":true,\"is_renting\":false,\"is_returning\":true}]}}";
        assertThat(new String(BluebikesFeed.parse(newer, 0).items().get("x"), StandardCharsets.UTF_8))
                .contains("\"renting\":false", "\"ebikes\":0");
    }

    @Test
    void aGoodPollHandsOverTheWholeListAndFollowsTheTtl() throws InterruptedException {
        server.expect(requestTo(URL)).andRespond(withSuccess(body(60, station("a", 1, 0, 9, 1), station("b", 0, 0, 5, 0)),
                MediaType.APPLICATION_JSON));

        assertThat(feed.poll()).isEqualTo(Duration.ofSeconds(60));
        assertThat(submitted).singleElement().isInstanceOfSatisfying(FeedUpdate.Replace.class,
                r -> assertThat(r.items()).containsOnlyKeys("a", "b"));
        assertThat(registry.get("placemap.live.feed.polls").tag("outcome", "ok").counter().count()).isEqualTo(1);
    }

    @Test
    void aTtlOutsideTheLimitsIsClamped() throws InterruptedException {
        server.expect(requestTo(URL)).andRespond(withSuccess(body(1, station("a", 1, 0, 9, 1)), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withSuccess(body(86_400, station("a", 1, 0, 9, 1)), MediaType.APPLICATION_JSON));

        assertThat(feed.poll()).isEqualTo(Duration.ofSeconds(15));
        assertThat(feed.poll()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void aFailedOrUnreadableAnswerIsReportedAndKeepsTheInterval() throws InterruptedException {
        server.expect(requestTo(URL)).andRespond(withServerError());
        server.expect(requestTo(URL)).andRespond(withSuccess("<html>maintenance</html>", MediaType.TEXT_HTML));
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"data\":{}}", MediaType.APPLICATION_JSON));

        assertThat(feed.poll()).isEqualTo(Duration.ofSeconds(60));
        assertThat(feed.poll()).isEqualTo(Duration.ofSeconds(60));
        assertThat(feed.poll()).isEqualTo(Duration.ofSeconds(60));
        assertThat(submitted).hasSize(3).allSatisfy(u -> assertThat(u).isInstanceOf(FeedUpdate.Failed.class));
        assertThat(registry.get("placemap.live.feed.polls").tag("outcome", "failed").counter().count()).isEqualTo(3);
    }
}
