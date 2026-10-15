package io.github.naveen11.placemap.livehub.feeds;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;

/**
 * Runs the polled feeds on Spring's {@link TaskScheduler}, a pool with a thread for each feed, so a slow feed never
 * delays another. Each feed runs as a {@link Trigger}d task: the scheduler starts the next poll only after the last
 * one returned, so polls of one feed never overlap, and a feed that hangs holds one thread however long it hangs.
 *
 * <p>Polls keep a steady cadence: the next one is due an interval after the last one was due, not after it ended. A
 * poll can name a different interval (the feed's ttl changed), and the trigger uses it from the next poll on.
 */
public final class FeedScheduler {

    private static final Logger log = LoggerFactory.getLogger(FeedScheduler.class);

    private final TaskScheduler scheduler;

    public FeedScheduler(TaskScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Polls {@code feed} now, then on its own cadence until the scheduler shuts down.
     *
     * @return asks for one extra poll now, unless one is running; the cadence stays as it is
     */
    public Runnable start(PolledFeed feed) {
        var polling = new Polling(Objects.requireNonNull(feed, "feed"));
        scheduler.schedule(polling, polling);
        return () -> {
            try {
                scheduler.schedule(polling::pollIfIdle, Instant.now());
            } catch (TaskRejectedException e) {
                // shutting down
            }
        };
    }

    /** One feed: its task, and the trigger that reads the interval its last poll asked for. */
    private static final class Polling implements Runnable, Trigger {

        private final PolledFeed feed;
        private final ReentrantLock running = new ReentrantLock(); // an extra poll never overlaps a scheduled one
        private volatile Duration interval;

        Polling(PolledFeed feed) {
            this.feed = feed;
            this.interval = feed.initialInterval();
        }

        @Override
        public void run() {
            running.lock();
            try {
                poll();
            } finally {
                running.unlock();
            }
        }

        void pollIfIdle() {
            if (running.tryLock()) {
                try {
                    poll();
                } finally {
                    running.unlock();
                }
            }
        }

        private void poll() {
            try {
                interval = feed.poll();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // shutting down
            } catch (RuntimeException e) {
                log.error("{}: poll failed unexpectedly", feed.name(), e); // feeds report their own failures
            }
        }

        @Override
        public Instant nextExecution(TriggerContext context) {
            Instant last = context.lastScheduledExecution();
            return last == null ? context.getClock().instant() : last.plus(interval);
        }
    }
}
