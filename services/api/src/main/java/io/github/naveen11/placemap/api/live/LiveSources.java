package io.github.naveen11.placemap.api.live;

import java.util.Map;
import java.util.Optional;

/**
 * Which place sources have live data in live-hub, and under which topic (ADR 0024). A place linked to one of them
 * gets a live key {@code topic:source_key}, the same key the map's live stream uses.
 */
public final class LiveSources {

    /** Source id in {@code data/sources.yaml} → live-hub topic. */
    public static final Map<String, String> TOPICS = Map.of("bluebikes_station_information", "bikes");

    private LiveSources() {
    }

    public static Optional<String> key(String source, String sourceKey) {
        String topic = source == null ? null : TOPICS.get(source);
        return topic == null || sourceKey == null ? Optional.empty() : Optional.of(topic + ":" + sourceKey);
    }
}
