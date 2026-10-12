package io.github.naveen11.placemap.livehub.state;

import java.util.Objects;

/**
 * What a frame is about, and so what it replaces in a client's mailbox: one item of a topic, a topic's status, or the
 * heartbeat. Only the newest frame per key waits for a slow client.
 *
 * @param topic the topic, or {@code null} for {@link #HEARTBEAT}
 * @param id    the item's id within the topic, or {@link #STATUS_ID}
 */
public record LiveKey(Topic topic, String id) {

    /** The id of a topic's status frame (stale or fresh); never a publisher id, which never starts with "_". */
    public static final String STATUS_ID = "_status";

    public static final LiveKey HEARTBEAT = new LiveKey(null, "_heartbeat");

    public LiveKey {
        Objects.requireNonNull(id, "id");
    }

    public static LiveKey item(Topic topic, String id) {
        return new LiveKey(Objects.requireNonNull(topic, "topic"), id);
    }

    public static LiveKey status(Topic topic) {
        return new LiveKey(Objects.requireNonNull(topic, "topic"), STATUS_ID);
    }
}
