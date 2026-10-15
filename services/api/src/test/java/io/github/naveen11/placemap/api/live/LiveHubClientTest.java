package io.github.naveen11.placemap.api.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.naveen11.placemap.api.Deadline;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The live-hub batch call: what it returns, and that it never makes a search wait past 150 ms. */
class LiveHubClientTest {

    private static final Duration TIMEOUT = Duration.ofMillis(150);
    private static final VirtualThreadTaskExecutor CALLS = new VirtualThreadTaskExecutor("live-batch-");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void returnsTheItemsAndTopicHealthLiveHubKnows() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://live-hub");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://live-hub/internal/live/batch"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"keys\":[\"bikes:a\",\"bikes:b\"]}"))
                .andRespond(withSuccess("""
                        {"version":7,"topics":{"bikes":{"stale":false,"updated":1700}},
                         "items":{"bikes:a":{"id":"a","bikes":3}}}""", MediaType.APPLICATION_JSON));
        var client = new LiveHubClient(builder.build(), TIMEOUT, registry, CALLS);

        LiveData data = client.fetch(List.of("bikes:a", "bikes:b"), Deadline.after(Duration.ofSeconds(1)));

        assertThat(data.status()).isEqualTo("ok");
        assertThat(data.items()).containsOnlyKeys("bikes:a");
        assertThat(data.items().get("bikes:a").path("bikes").asInt()).isEqualTo(3);
        assertThat(data.topics().get("bikes").path("stale").asBoolean()).isFalse();
        assertThat(count("ok")).isEqualTo(1);
    }

    @Test
    void aSlowLiveHubIsCutOffAtTheTimeout() {
        // Answers after a full second, as a stuck live-hub would (without a network).
        RestClient slow = RestClient.builder().baseUrl("http://live-hub")
                .requestFactory((URI uri, HttpMethod method) -> new MockClientHttpRequest(method, uri) {
                    @Override
                    protected ClientHttpResponse executeInternal() {
                        try {
                            Thread.sleep(1_000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return new MockClientHttpResponse("{\"items\":{}}".getBytes(), HttpStatus.OK);
                    }
                })
                .build();
        var client = new LiveHubClient(slow, TIMEOUT, registry, CALLS);

        long started = System.nanoTime();
        LiveData data = client.fetch(List.of("bikes:a"), Deadline.after(Duration.ofSeconds(1)));
        long tookMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(data.status()).isEqualTo("unavailable");
        assertThat(data.items()).isEmpty();
        assertThat(tookMillis).isBetween(140L, 400L);
        assertThat(count("timeout")).isEqualTo(1);
    }

    @Test
    void neverWaitsLongerThanTheRequestHasLeft() {
        var client = new LiveHubClient(RestClient.builder().baseUrl("http://live-hub").build(), TIMEOUT, registry, CALLS);
        LiveData data = client.fetch(List.of("bikes:a"), Deadline.after(Duration.ZERO));
        assertThat(data.status()).isEqualTo("unavailable");
        assertThat(count("no_time")).isEqualTo(1);
    }

    @Test
    void anErrorOrUnreadableAnswerMeansNoLiveData() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://live-hub");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://live-hub/internal/live/batch")).andRespond(withServerError());
        server.expect(requestTo("http://live-hub/internal/live/batch"))
                .andRespond(withSuccess("{\"unexpected\":true}", MediaType.APPLICATION_JSON));
        var client = new LiveHubClient(builder.build(), TIMEOUT, registry, CALLS);

        assertThat(client.fetch(List.of("bikes:a"), Deadline.after(Duration.ofSeconds(1))).status()).isEqualTo("unavailable");
        assertThat(client.fetch(List.of("bikes:a"), Deadline.after(Duration.ofSeconds(1))).status()).isEqualTo("unavailable");
        assertThat(count("error")).isEqualTo(2);
    }

    @Test
    void turnedOffMeansNoLiveData() {
        var client = new LiveHubClient(null, TIMEOUT, registry, CALLS);
        assertThat(client.fetch(List.of("bikes:a"), Deadline.after(Duration.ofSeconds(1))).status()).isEqualTo("unavailable");
        assertThat(count("disabled")).isEqualTo(1);
    }

    private double count(String outcome) {
        return registry.get("placemap.nearby.live").tag("outcome", outcome).counter().count();
    }
}
