package io.github.naveen11.placemap.api.live;

import io.github.naveen11.placemap.api.Deadline;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Asks live-hub for the live data of many places at once ({@code POST /internal/live/batch}, ADR 0024). Never fails
 * and never waits longer than {@code timeout} or the request's own deadline: past either, the answer is
 * {@link LiveData#unavailable()} and nearby results go out without live badges.
 *
 * <p>The call needs the places' live keys, so it starts once the (shared, cached) nearby result is known rather than
 * alongside the database and routing steps. It runs on its own virtual thread so the wait can be cut off exactly; the
 * HTTP client's own timeout ends the abandoned call shortly after.
 */
public class LiveHubClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LiveHubClient.class);

    private final RestClient http;
    private final Duration timeout;
    private final MeterRegistry registry;
    private final ExecutorService calls =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("live-batch-", 1).factory());

    /** @param http a client for live-hub's base URL, or null when live data is turned off */
    public LiveHubClient(RestClient http, Duration timeout, MeterRegistry registry) {
        this.http = http;
        this.timeout = timeout;
        this.registry = registry;
    }

    /** Live data for {@code keys}, or {@link LiveData#unavailable()}. Call only with at least one key. */
    public LiveData fetch(Collection<String> keys, Deadline deadline) {
        if (http == null) {
            return outcome("disabled", LiveData.unavailable());
        }
        long budget = Math.min(timeout.toNanos(), deadline.remaining().toNanos());
        if (budget <= 0) {
            return outcome("no_time", LiveData.unavailable());
        }
        CompletableFuture<LiveData> call = CompletableFuture.supplyAsync(() -> post(List.copyOf(keys)), calls);
        try {
            return outcome("ok", call.get(budget, TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            call.cancel(true);
            return outcome("timeout", LiveData.unavailable());
        } catch (ExecutionException e) {
            log.debug("live-hub batch failed: {}", e.getCause().toString());
            return outcome("error", LiveData.unavailable());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return outcome("error", LiveData.unavailable());
        }
    }

    private LiveData post(List<String> keys) {
        JsonNode body = http.post().uri("/internal/live/batch").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("keys", keys)).retrieve().body(JsonNode.class);
        if (body == null || !body.path("items").isObject()) {
            throw new IllegalStateException("live-hub answered without items");
        }
        return new LiveData(LiveData.OK, fields(body.path("topics")), fields(body.path("items")));
    }

    private static Map<String, JsonNode> fields(JsonNode object) {
        var map = new LinkedHashMap<String, JsonNode>();
        object.properties().forEach(e -> map.put(e.getKey(), e.getValue()));
        return map;
    }

    private LiveData outcome(String outcome, LiveData data) {
        registry.counter("placemap.nearby.live", "outcome", outcome).increment();
        return data;
    }

    @Override
    public void close() {
        calls.close();
    }
}
