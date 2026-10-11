package io.github.naveen11.placemap.livehub.feeds;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the polled feeds (plan, showcase 2). One platform thread only fires triggers; each poll runs on its own virtual
 * thread, so a slow feed never delays another feed's trigger. A per-feed flag stops polls from overlapping: if the
 * previous poll of a feed is still running when its next trigger fires, that trigger is skipped. A feed that hangs
 * therefore holds one thread at most, however long it hangs.
 *
 * <p>Triggers keep a steady cadence: the next one is scheduled when the current one fires, not when its poll ends.
 * When a poll reports a different interval (the feed's ttl changed), the waiting trigger is moved to match. All
 * scheduling happens on the trigger thread, so none of it needs a lock.
 */
public final class FeedScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FeedScheduler.class);

    /** One feed's scheduling state. Everything but {@code polling} is touched only on the trigger thread. */
    private static final class Scheduled {
        final PolledFeed feed;
        final AtomicBoolean polling = new AtomicBoolean();
        Duration interval;
        long firedAtNanos;
        ScheduledFuture<?> next;
        long skipped;

        Scheduled(PolledFeed feed) {
            this.feed = feed;
            this.interval = feed.initialInterval();
        }
    }

    private final ScheduledExecutorService triggers = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("live-feed-trigger").daemon().factory());
    private final ExecutorService polls = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("live-poll-", 0).factory());

    /**
     * Polls {@code feed} now, then on its own cadence until {@link #close()}.
     *
     * @return asks for one extra poll now, unless one is running; the cadence stays as it is
     */
    public Runnable start(PolledFeed feed) {
        var scheduled = new Scheduled(Objects.requireNonNull(feed, "feed"));
        triggers.execute(() -> fire(scheduled));
        return () -> {
            if (scheduled.polling.compareAndSet(false, true)) {
                try {
                    polls.execute(() -> poll(scheduled));
                } catch (RejectedExecutionException e) {
                    scheduled.polling.set(false); // shutting down
                }
            }
        };
    }

    private void fire(Scheduled s) {
        s.firedAtNanos = System.nanoTime();
        if (s.polling.compareAndSet(false, true)) {
            try {
                polls.execute(() -> poll(s));
            } catch (RejectedExecutionException e) {
                s.polling.set(false);
                return; // shutting down
            }
        } else {
            s.skipped++;
            log.warn("{}: previous poll still running, skipping this one ({} skipped so far)", s.feed.name(), s.skipped);
        }
        schedule(s, s.interval.toNanos());
    }

    private void poll(Scheduled s) {
        Duration next = null;
        try {
            next = s.feed.poll();
        } catch (InterruptedException e) {
            // close(): stop quietly.
        } catch (RuntimeException e) {
            log.error("{}: poll failed unexpectedly", s.feed.name(), e); // feeds report their own failures
        } finally {
            s.polling.set(false);
        }
        if (next != null) {
            Duration interval = next;
            try {
                triggers.execute(() -> retime(s, interval));
            } catch (RejectedExecutionException e) {
                // shutting down
            }
        }
    }

    /** Moves the waiting trigger to {@code interval} after the last one, if the feed asked for a new interval. */
    private void retime(Scheduled s, Duration interval) {
        if (interval.equals(s.interval)) {
            return;
        }
        s.interval = interval;
        if (s.next != null && s.next.cancel(false)) { // false: it already fired, and used the new interval
            schedule(s, Math.max(0, s.firedAtNanos + interval.toNanos() - System.nanoTime()));
        }
    }

    private void schedule(Scheduled s, long delayNanos) {
        try {
            s.next = triggers.schedule(() -> fire(s), delayNanos, TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            // shutting down
        }
    }

    @Override
    public void close() {
        triggers.shutdownNow();
        polls.shutdownNow();
        try {
            polls.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
