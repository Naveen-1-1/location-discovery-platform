package io.github.naveen11.placemap.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Behaviour of {@link SerialDrain}; the JCStress tests cover the races between signal and the end of a run. */
@Timeout(20)
class SerialDrainTest {

    /** An executor that only queues tasks, so a test decides when each one runs. */
    static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> queued = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            queued.add(task);
        }

        void runNext() {
            queued.remove().run();
        }
    }

    @Test
    void signalsBeforeTheTaskRunsSubmitItOnce() {
        var executor = new ManualExecutor();
        var runs = new AtomicInteger();
        var drain = new SerialDrain(executor, runs::incrementAndGet, () -> false);

        drain.signal();
        drain.signal();
        drain.signal();
        assertThat(executor.queued).hasSize(1);
        executor.runNext();
        assertThat(runs.get()).isEqualTo(1);
        assertThat(executor.queued).isEmpty();
    }

    @Test
    void aSignalDuringARunResubmitsTheTaskInsteadOfLoopingOnTheSameThread() {
        var executor = new ManualExecutor();
        var work = new ConcurrentLinkedQueue<Integer>();
        var drained = new AtomicInteger();
        var drain = new SerialDrain[1];
        drain[0] = new SerialDrain(executor, () -> {
            while (work.poll() != null) {
                drained.incrementAndGet();
            }
            // Arrives after the last poll but while the task still counts as running: must not be lost.
            if (drained.get() == 1) {
                work.add(2);
                drain[0].signal();
            }
        }, () -> !work.isEmpty());

        work.add(1);
        drain[0].signal();
        executor.runNext();
        assertThat(drained.get()).isEqualTo(1);
        assertThat(executor.queued).as("resubmitted for the late item").hasSize(1);
        executor.runNext();
        assertThat(drained.get()).isEqualTo(2);
        assertThat(executor.queued).isEmpty();
    }

    @Test
    void interruptReachesTheRunningTaskButNotTheThreadsNextTask() {
        var executor = new ManualExecutor();
        var sawInterrupt = new AtomicBoolean();
        var drain = new SerialDrain[1];
        drain[0] = new SerialDrain(executor, () -> {
            drain[0].interruptRunning(); // as the watchdog would, from another thread, mid-write
            sawInterrupt.set(Thread.currentThread().isInterrupted());
        }, () -> false);

        drain[0].signal();
        executor.runNext();
        assertThat(sawInterrupt).isTrue();
        assertThat(Thread.interrupted()).as("interrupt cleared before the pool thread moves on").isFalse();
    }

    @Test
    void interruptWhileIdleDoesNothing() {
        var executor = new ManualExecutor();
        var drain = new SerialDrain(executor, () -> { }, () -> false);
        drain.interruptRunning();
        drain.signal();
        drain.interruptRunning(); // queued, not running
        executor.runNext();
        assertThat(Thread.interrupted()).isFalse();
    }

    @Test
    void aRejectedSubmitCanBeRetriedLater() {
        var refuse = new AtomicBoolean(true);
        var executor = new ManualExecutor();
        Executor flaky = task -> {
            if (refuse.get()) {
                throw new RejectedExecutionException("shutting down");
            }
            executor.execute(task);
        };
        var drain = new SerialDrain(flaky, () -> { }, () -> false);
        assertThatThrownBy(drain::signal).isInstanceOf(RejectedExecutionException.class);
        refuse.set(false);
        drain.signal();
        assertThat(executor.queued).hasSize(1);
    }

    @Test
    void manyProducersOnAPoolNeverOverlapAndNeverLoseWork() throws Exception {
        int producers = 4;
        int perProducer = 20_000;
        var pool = Executors.newFixedThreadPool(4);
        try {
            var work = new ConcurrentLinkedQueue<Integer>();
            var drained = new AtomicInteger();
            var running = new AtomicInteger();
            var overlaps = new AtomicInteger();
            var drain = new SerialDrain(pool, () -> {
                if (running.incrementAndGet() > 1) {
                    overlaps.incrementAndGet();
                }
                int n = 0;
                while (n < 64 && work.poll() != null) { // a bounded turn, like a real drain
                    n++;
                }
                drained.addAndGet(n);
                running.decrementAndGet();
            }, () -> !work.isEmpty());

            var start = new CountDownLatch(1);
            var threads = new Thread[producers];
            for (int p = 0; p < producers; p++) {
                threads[p] = Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                    for (int i = 0; i < perProducer; i++) {
                        work.add(i);
                        drain.signal();
                    }
                });
            }
            start.countDown();
            for (Thread t : threads) {
                t.join();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (drained.get() < producers * perProducer && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertThat(drained.get()).isEqualTo(producers * perProducer);
            assertThat(overlaps.get()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }
}
