package io.github.naveen11.placemap.livehub.feeds;

import static org.assertj.core.api.Assertions.assertThat;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Cadence, interval changes and overlap protection, on a real Spring scheduler with short intervals. */
@Timeout(10)
class FeedSchedulerTest {

    private final ThreadPoolTaskScheduler threads = new ThreadPoolTaskScheduler();
    private final FeedScheduler scheduler;

    FeedSchedulerTest() {
        threads.setPoolSize(4);
        threads.initialize();
        scheduler = new FeedScheduler(threads);
    }

    @AfterEach
    void stop() {
        threads.shutdown();
    }

    /** A feed that counts polls and how many run at once, and asks for {@code interval} after each one. */
    static final class CountingFeed implements PolledFeed {
        final AtomicInteger polls = new AtomicInteger();
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger mostAtOnce = new AtomicInteger();
        final Duration initial;
        final Duration interval;
        final long pollMillis;

        CountingFeed(Duration initial, Duration interval, long pollMillis) {
            this.initial = initial;
            this.interval = interval;
            this.pollMillis = pollMillis;
        }

        @Override
        public String name() {
            return "test";
        }

        @Override
        public Duration initialInterval() {
            return initial;
        }

        @Override
        public Duration poll() throws InterruptedException {
            mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                Thread.sleep(pollMillis);
                polls.incrementAndGet();
                return interval;
            } finally {
                running.decrementAndGet();
            }
        }
    }

    @Test
    void theFirstPollsAnswerSetsTheCadenceAtOnce() throws InterruptedException {
        // Starts assuming a minute, but the feed says 50 ms: the second poll must not wait a minute.
        var feed = new CountingFeed(Duration.ofMinutes(1), Duration.ofMillis(50), 0);
        scheduler.start(feed);
        awaitPolls(feed, 5);
    }

    @Test
    void aSlowPollIsNeverOverlappedByTheNextTrigger() throws InterruptedException {
        // Due every 20 ms, each poll takes 150 ms, and extra polls are asked for: still one at a time.
        var feed = new CountingFeed(Duration.ofMillis(20), Duration.ofMillis(20), 150);
        Runnable pollNow = scheduler.start(feed);
        pollNow.run();
        pollNow.run();
        awaitPolls(feed, 3);
        assertThat(feed.mostAtOnce.get()).isEqualTo(1);
    }

    @Test
    void shuttingTheSchedulerDownStopsPolling() throws InterruptedException {
        var feed = new CountingFeed(Duration.ofMillis(10), Duration.ofMillis(10), 0);
        scheduler.start(feed);
        awaitPolls(feed, 2);
        threads.shutdown();
        int after = feed.polls.get();
        new CountDownLatch(1).await(100, TimeUnit.MILLISECONDS);
        assertThat(feed.polls.get()).isLessThanOrEqualTo(after + 1); // at most one already on its way
    }

    private static void awaitPolls(CountingFeed feed, int count) {
        await().atMost(Duration.ofSeconds(5)).until(() -> feed.polls.get() >= count);
    }
}
