package io.github.naveen11.placemap.livehub.state;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * A kind of live data a client can subscribe to. Each topic comes from one feed and has its own items, keyed by the
 * publisher's id, and its own health: it is stale once its feed has not answered for 3× the feed's ttl (ADR 0024).
 */
public enum Topic {

    /** Bluebikes {@code station_status}: bikes and free docks per dock, keyed by GBFS {@code station_id}. */
    BIKES("bikes", Duration.ofSeconds(60));

    private final String wire;
    private final Duration defaultTtl;

    Topic(String wire, Duration defaultTtl) {
        this.wire = wire;
        this.defaultTtl = defaultTtl;
    }

    /** The name in URLs, event names and batch keys. */
    public String wire() {
        return wire;
    }

    /** The ttl assumed until the feed states its own. */
    public Duration defaultTtl() {
        return defaultTtl;
    }

    public static Optional<Topic> fromWire(String name) {
        return Arrays.stream(values()).filter(t -> t.wire.equals(name)).findFirst();
    }
}
