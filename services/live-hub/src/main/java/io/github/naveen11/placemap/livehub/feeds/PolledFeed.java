package io.github.naveen11.placemap.livehub.feeds;

import java.time.Duration;

/** A feed read by polling. Implementations hand what they read to the snapshot writer and never throw. */
public interface PolledFeed {

    String name();

    /** How long to wait before the first poll's successor, until a poll states its own interval. */
    Duration initialInterval();

    /**
     * Reads the feed once and hands the result (or the failure) to the snapshot writer.
     *
     * @return how long until the next poll should start, for example from the feed's own ttl
     * @throws InterruptedException if live-hub is shutting down
     */
    Duration poll() throws InterruptedException;
}
