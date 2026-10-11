package io.github.naveen11.placemap.livehub.state;

/** Where feeds hand their updates: the {@link SnapshotWriter}'s queue. */
@FunctionalInterface
public interface UpdateSink {

    /**
     * Hands over an update, waiting a bounded time for room.
     *
     * @return {@code false} if there was no room and the update was dropped
     * @throws InterruptedException if live-hub is shutting down
     */
    boolean submit(FeedUpdate update) throws InterruptedException;
}
