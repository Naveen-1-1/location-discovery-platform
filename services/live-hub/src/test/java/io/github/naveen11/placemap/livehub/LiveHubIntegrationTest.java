package io.github.naveen11.placemap.livehub;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole path on a real Tomcat: a WireMock Bluebikes feed, the poller, the snapshot writer, a real SSE client, and
 * the batch endpoint. Ends with the feed timing out (a short version of C6): the stream reports the topic stale
 * within 3× the feed's ttl, and the thread count stays flat. Binds local ports, so it runs on the owner's machine.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "placemap.live.tick=50ms",
        "placemap.live.bluebikes.min-interval=1s",
        "placemap.live.bluebikes.timeout=500ms"})
@Timeout(60)
class LiveHubIntegrationTest {

    static final WireMockServer GBFS = new WireMockServer(options().dynamicPort());

    static {
        GBFS.start();
        feedSays(3); // before the app starts, so its first poll finds data
    }

    @DynamicPropertySource
    static void feedUrl(DynamicPropertyRegistry registry) {
        registry.add("placemap.live.bluebikes.url", () -> GBFS.baseUrl() + "/gbfs/en/station_status.json");
    }

    @AfterAll
    static void stopFeed() {
        GBFS.stop();
    }

    /** The feed answers with dock "a" holding {@code bikes} bikes and a 1 s ttl. */
    static void feedSays(int bikes) {
        GBFS.resetAll();
        GBFS.stubFor(get("/gbfs/en/station_status.json").willReturn(okJson("""
                {"last_updated":1791744266,"ttl":1,"version":"1.1","data":{"stations":[
                 {"station_id":"a","num_bikes_available":%d,"num_ebikes_available":0,"num_docks_available":9,
                  "is_installed":1,"is_renting":1,"is_returning":1}]}}""".formatted(bikes))));
    }

    @LocalServerPort
    int port;

    @Autowired
    MeterRegistry registry;

    record Event(String name, String data) {
    }

    @Test
    void streamsChangesAnswersBatchesAndReportsAFeedOutage() throws Exception {
        var http = HttpClient.newHttpClient();
        var events = new LinkedBlockingQueue<Event>();
        HttpResponse<Stream<String>> stream = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/live?topics=bikes")).build(),
                HttpResponse.BodyHandlers.ofLines());
        assertThat(stream.statusCode()).isEqualTo(200);
        assertThat(stream.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("text/event-stream"));
        Thread reader = Thread.ofVirtual().start(() -> read(stream.body(), events));
        try {
            Predicate<Event> threeBikes = e -> e.data().contains("\"id\":\"a\",\"bikes\":3");
            Event first = next(events, e -> true, "the first event");
            assertThat(first.name()).isEqualTo("snapshot");
            if (!threeBikes.test(first)) { // the first poll may land just after the client connected
                next(events, threeBikes, "dock a with 3 bikes");
            }

            feedSays(2);
            next(events, e -> e.name().equals("bikes") && e.data().contains("\"bikes\":2"), "the change to 2 bikes");

            String batch = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/live/batch"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"keys\":[\"bikes:a\"]}")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertThat(batch).contains("\"bikes:a\":{\"id\":\"a\",\"bikes\":2");

            int threadsBefore = ManagementFactory.getThreadMXBean().getThreadCount();
            long outageStarted = System.nanoTime();
            GBFS.resetAll();
            GBFS.stubFor(get("/gbfs/en/station_status.json").willReturn(aResponse().withFixedDelay(2_000)
                    .withHeader("Content-Type", "application/json").withBody("{}")));
            next(events, e -> e.name().equals("status") && e.data().contains("\"stale\":true"), "the topic going stale");
            double seconds = (System.nanoTime() - outageStarted) / 1e9;
            assertThat(seconds).as("stale within 3 × the 1 s ttl, plus a poll and a tick").isLessThan(5.5);
            assertThat(ManagementFactory.getThreadMXBean().getThreadCount()).isLessThanOrEqualTo(threadsBefore + 2);
            assertThat(registry.get("placemap.live.feed.polls").tag("outcome", "failed").counter().count()).isPositive();
        } finally {
            reader.interrupt();
            stream.body().close();
        }
    }

    private static void read(Stream<String> lines, LinkedBlockingQueue<Event> events) {
        var name = new StringBuilder();
        List<String> data = new ArrayList<>();
        try (lines) {
            lines.forEach(line -> {
                if (line.startsWith("event: ")) {
                    name.setLength(0);
                    name.append(line.substring(7));
                } else if (line.startsWith("data: ")) {
                    data.add(line.substring(6));
                } else if (line.isEmpty() && !data.isEmpty()) {
                    events.add(new Event(name.isEmpty() ? "message" : name.toString(), String.join("\n", data)));
                    name.setLength(0);
                    data.clear();
                }
            });
        } catch (RuntimeException e) {
            // the test closed the stream
        }
    }

    private static Event next(LinkedBlockingQueue<Event> events, Predicate<Event> wanted, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Event e = events.poll(100, TimeUnit.MILLISECONDS);
            if (e != null && wanted.test(e)) {
                return e;
            }
        }
        return fail("no event within 15 s: " + what);
    }
}
