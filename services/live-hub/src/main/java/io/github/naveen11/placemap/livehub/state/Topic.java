package io.github.naveen11.placemap.livehub.state;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * A kind of live data. Each topic comes from one feed and has its own items, keyed by the publisher's id, and its own
 * health: it is stale once its feed has not answered for 3× the feed's ttl (ADR 0024).
 *
 * <p>Streamed topics go to every client that subscribes. A topic that is not streamed is too big to send everyone
 * (every stop's next departures, about 2 MB); clients ask for one item of it at a time instead.
 */
public enum Topic {

    /** Bluebikes {@code station_status}: bikes and free docks per dock, keyed by GBFS {@code station_id}. */
    BIKES("bikes", Duration.ofSeconds(60), true),

    /** MBTA alerts in effect, per stop or station they affect, keyed by GTFS {@code stop_id}. */
    ALERTS("alerts", Duration.ofSeconds(30), true),

    /** MBTA vehicles and where they are, keyed by vehicle id. */
    VEHICLES("vehicles", Duration.ofSeconds(15), true),

    /** The next MBTA departures per stop or station, keyed by GTFS {@code stop_id}. Not streamed. */
    DEPARTURES("departures", Duration.ofSeconds(15), false);

    private final String wire;
    private final Duration defaultTtl;
    private final boolean streamed;

    Topic(String wire, Duration defaultTtl, boolean streamed) {
        this.wire = wire;
        this.defaultTtl = defaultTtl;
        this.streamed = streamed;
    }

    /** Whether clients can subscribe to it on {@code /live}. */
    public boolean streamed() {
        return streamed;
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
