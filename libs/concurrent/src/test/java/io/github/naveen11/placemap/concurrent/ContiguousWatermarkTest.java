package io.github.naveen11.placemap.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Behaviour of {@link ContiguousWatermark} one case at a time; Lincheck and JCStress cover the interleavings. */
@Timeout(30)
class ContiguousWatermarkTest {

    @Test
    void startsBeforeTheFirstBatch() {
        assertThat(new ContiguousWatermark().current()).isEqualTo(-1);
    }

    @Test
    void advancesInOrder() {
        var w = new ContiguousWatermark();
        w.markDone(0);
        assertThat(w.current()).isZero();
        w.markDone(1);
        assertThat(w.current()).isEqualTo(1);
    }

    @Test
    void waitsForTheGapThenJumpsOverEverythingBehindIt() {
        var w = new ContiguousWatermark();
        w.markDone(3);
        w.markDone(1);
        w.markDone(2);
        assertThat(w.current()).isEqualTo(-1);
        assertThat(w.pendingSize()).isEqualTo(3);

        w.markDone(0);
        assertThat(w.current()).isEqualTo(3);
        assertThat(w.pendingSize()).isZero();
    }

    @Test
    void repeatsAreHarmless() {
        var w = new ContiguousWatermark();
        w.markDone(0);
        w.markDone(0);
        w.markDone(2);
        w.markDone(2);
        assertThat(w.current()).isZero();
        w.markDone(1);
        assertThat(w.current()).isEqualTo(2);
        assertThat(w.pendingSize()).isZero();
    }

    @Test
    void rejectsNegativeBatchNumbers() {
        assertThatThrownBy(() -> new ContiguousWatermark().markDone(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Eight threads mark 200,000 batches in a shuffled order (with every batch marked twice, as a resumed run would)
     * while a reader polls. The reader must never see the watermark go backwards, and at the end it must cover
     * everything with no stale entries left behind.
     */
    @Test
    void concurrentMarkingEndsAtTheFullPrefix() throws InterruptedException {
        int threads = 8;
        long batches = 200_000;
        List<Long> order = new ArrayList<>(LongStream.range(0, batches).boxed().toList());
        order.addAll(order);
        Collections.shuffle(order, new Random(42));

        var w = new ContiguousWatermark();
        var start = new CountDownLatch(1);
        var done = new AtomicBoolean();
        var wentBackwards = new AtomicLong(-2);
        Thread reader = Thread.ofPlatform().start(() -> {
            long last = -1;
            while (!done.get()) {
                long now = w.current();
                if (now < last) {
                    wentBackwards.set(now);
                }
                last = now;
            }
        });
        List<Thread> writers = new ArrayList<>();
        int slice = order.size() / threads;
        for (int t = 0; t < threads; t++) {
            List<Long> mine = order.subList(t * slice, (t + 1) * slice);
            writers.add(Thread.ofPlatform().start(() -> {
                awaitUninterruptibly(start);
                mine.forEach(w::markDone);
            }));
        }
        start.countDown();
        for (Thread t : writers) {
            t.join();
        }
        done.set(true);
        reader.join();

        assertThat(wentBackwards.get()).as("watermark went backwards").isEqualTo(-2);
        assertThat(w.current()).isEqualTo(batches - 1);
        assertThat(w.pendingSize()).isZero();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }
}
