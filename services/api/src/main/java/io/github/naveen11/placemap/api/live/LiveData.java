package io.github.naveen11.placemap.api.live;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Live data for one nearby answer.
 *
 * @param status {@code ok}, or {@code unavailable} when live-hub was slow, down or gave an unreadable answer
 * @param topics per topic, {@code {"stale": bool, "updated": ms|null}}; empty when unavailable
 * @param items  live key → the item, for the keys live-hub knows
 */
public record LiveData(String status, Map<String, JsonNode> topics, Map<String, JsonNode> items) {

    public static final String OK = "ok";
    public static final String UNAVAILABLE = "unavailable";

    public static LiveData unavailable() {
        return new LiveData(UNAVAILABLE, Map.of(), Map.of());
    }
}
