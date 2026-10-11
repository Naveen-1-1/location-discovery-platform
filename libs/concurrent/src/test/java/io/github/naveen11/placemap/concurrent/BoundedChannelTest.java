package io.github.naveen11.placemap.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Behaviour of {@link BoundedChannel} one case at a time; Lincheck and JCStress cover the interleavings. */
@Timeout(10)
class BoundedChannelTest {

    @Test
    void deliversInFifoOrder() throws InterruptedException {
        var ch = new BoundedChannel<Integer>(3);
        ch.put(1);
        ch.put(2);
        ch.put(3);
        assertThat(ch.take()).isEqualTo(1);
        ch.put(4); // wraps around the ring buffer
        assertThat(List.of(ch.take(), ch.take(), ch.take())).containsExactly(2, 3, 4);
        assertThat(ch.size()).isZero();
    }

    @Test
    void offerRefusesWhenFullAndPollReturnsNullWhenEmpty() {
        var ch = new BoundedChannel<String>(1);
        assertThat(ch.poll()).isNull();
        assertThat(ch.offer("a")).isTrue();
        assertThat(ch.offer("b")).isFalse();
        assertThat(ch.size()).isEqualTo(1);
        assertThat(ch.poll()).isEqualTo("a");
    }

    @Test
    void putWaitsWhileFull() throws Exception {
        var ch = new BoundedChannel<Integer>(1);
        ch.put(1);
        Thread producer = Thread.ofPlatform().start(() -> uninterruptibly(() -> ch.put(2)));
        awaitWaiting(producer);

        assertThat(ch.take()).isEqualTo(1); // frees a slot and wakes the producer
        producer.join();
        assertThat(ch.take()).isEqualTo(2);
    }

    @Test
    void takeWaitsWhileEmpty() throws Exception {
        var ch = new BoundedChannel<Integer>(1);
        var taken = new CompletableFuture<Integer>();
        Thread consumer = Thread.ofPlatform().start(() -> uninterruptibly(() -> taken.complete(ch.take())));
        awaitWaiting(consumer);

        ch.put(7);
        assertThat(taken.get(5, TimeUnit.SECONDS)).isEqualTo(7);
    }

    @Test
    void closeLetsConsumersDrainThenSignalsEndOfStream() throws InterruptedException {
        var ch = new BoundedChannel<Integer>(4);
        ch.put(1);
        ch.put(2);
        ch.close();
        ch.close(); // idempotent

        assertThat(ch.take()).isEqualTo(1);
        assertThat(ch.poll()).isEqualTo(2);
        assertThat(ch.take()).isNull();
        assertThat(ch.take()).isNull(); // end of stream is sticky
    }

    @Test
    void putAfterCloseIsRejected() {
        var ch = new BoundedChannel<Integer>(4);
        ch.close();
        assertThatThrownBy(() -> ch.put(1)).isInstanceOf(ChannelClosedException.class);
        assertThatThrownBy(() -> ch.offer(1)).isInstanceOf(ChannelClosedException.class);
        assertThat(ch.size()).isZero();
    }

    @Test
    void closeWakesWaitingProducersAndConsumers() throws Exception {
        var full = new BoundedChannel<Integer>(1);
        full.put(1);
        var errors = new ConcurrentLinkedQueue<Throwable>();
        Thread producer = Thread.ofPlatform().start(() -> captureFailure(errors, () -> full.put(2)));
        var empty = new BoundedChannel<Integer>(1);
        var consumed = new AtomicReference<Object>("not yet");
        Thread consumer = Thread.ofPlatform().start(() -> uninterruptibly(() -> consumed.set(empty.take())));
        awaitWaiting(producer);
        awaitWaiting(consumer);

        full.close();
        empty.close();
        producer.join();
        consumer.join();

        assertThat(errors).singleElement().isInstanceOf(ChannelClosedException.class);
        assertThat(consumed.get()).isNull();
        assertThat(full.take()).isEqualTo(1); // the item accepted before close is still delivered
        assertThat(full.take()).isNull();
    }

    @Test
    void failDropsBufferedItemsAndEveryCallThrowsWithTheCause() {
        var ch = new BoundedChannel<Integer>(4);
        ch.offer(1);
        ch.offer(2);
        var cause = new IllegalStateException("parse stage broke");
        ch.fail(cause);

        assertThat(ch.size()).isZero();
        assertThatThrownBy(ch::take).isInstanceOf(ChannelFailedException.class).hasCause(cause);
        assertThatThrownBy(ch::poll).isInstanceOf(ChannelFailedException.class).hasCause(cause);
        assertThatThrownBy(() -> ch.put(3)).isInstanceOf(ChannelFailedException.class).hasCause(cause);
        assertThatThrownBy(() -> ch.offer(3)).isInstanceOf(ChannelFailedException.class).hasCause(cause);
    }

    @Test
    void firstFailureWinsAndCloseCannotUndoIt() {
        var ch = new BoundedChannel<Integer>(1);
        var first = new RuntimeException("first");
        ch.fail(first);
        ch.fail(new RuntimeException("second"));
        ch.close();
        assertThatThrownBy(ch::poll).hasCause(first);
    }

    @Test
    void failAfterCloseDropsWhatWasNotYetDrained() {
        var ch = new BoundedChannel<Integer>(2);
        ch.offer(1);
        ch.close();
        var cause = new RuntimeException("consumer broke");
        ch.fail(cause);
        assertThatThrownBy(ch::take).hasCause(cause);
    }

    @Test
    void failWakesWaitingProducersAndConsumers() throws Exception {
        var full = new BoundedChannel<Integer>(1);
        full.put(1);
        var empty = new BoundedChannel<Integer>(1);
        var errors = new ConcurrentLinkedQueue<Throwable>();
        Thread producer = Thread.ofPlatform().start(() -> captureFailure(errors, () -> full.put(2)));
        Thread consumer = Thread.ofPlatform().start(() -> captureFailure(errors, empty::take));
        awaitWaiting(producer);
        awaitWaiting(consumer);

        var cause = new RuntimeException("stop");
        full.fail(cause);
        empty.fail(cause);
        producer.join();
        consumer.join();

        assertThat(errors).hasSize(2).allSatisfy(e -> assertThat(e)
                .isInstanceOf(ChannelFailedException.class)
                .hasCause(cause));
    }

    @Test
    void interruptingAWaitingConsumerLeavesTheChannelUsable() throws Exception {
        var ch = new BoundedChannel<Integer>(1);
        var interrupted = new CompletableFuture<Boolean>();
        Thread consumer = Thread.ofPlatform().start(() -> {
            try {
                ch.take();
                interrupted.complete(false);
            } catch (InterruptedException e) {
                interrupted.complete(true);
            }
        });
        awaitWaiting(consumer);
        consumer.interrupt();

        assertThat(interrupted.get(5, TimeUnit.SECONDS)).isTrue();
        ch.put(1);
        assertThat(ch.take()).isEqualTo(1);
    }

    @Test
    void rejectsNullItemsAndBadCapacity() {
        var ch = new BoundedChannel<Integer>(1);
        assertThatThrownBy(() -> ch.put(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ch.offer(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ch.fail(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BoundedChannel<Integer>(0)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Four producers and four consumers through a channel much smaller than the traffic: every item arrives exactly
     * once, each producer's items stay in order, and closing after the producers finish ends every consumer.
     */
    @Test
    void manyProducersAndConsumersLoseAndDuplicateNothing() throws Exception {
        int producers = 4;
        int consumers = 4;
        int perProducer = 20_000;
        var ch = new BoundedChannel<long[]>(8);

        List<Thread> producerThreads = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            int id = p;
            producerThreads.add(Thread.ofPlatform().start(() -> uninterruptibly(() -> {
                for (int i = 0; i < perProducer; i++) {
                    ch.put(new long[] {id, i});
                }
            })));
        }
        List<List<long[]>> received = new ArrayList<>();
        List<Thread> consumerThreads = new ArrayList<>();
        for (int c = 0; c < consumers; c++) {
            List<long[]> mine = new ArrayList<>();
            received.add(mine);
            consumerThreads.add(Thread.ofPlatform().start(() -> uninterruptibly(() -> {
                for (long[] item; (item = ch.take()) != null; ) {
                    mine.add(item);
                }
            })));
        }
        for (Thread t : producerThreads) {
            t.join();
        }
        ch.close();
        for (Thread t : consumerThreads) {
            t.join();
        }

        boolean[][] seen = new boolean[producers][perProducer];
        int total = 0;
        for (List<long[]> mine : received) {
            int[] lastSeq = new int[producers];
            Arrays.fill(lastSeq, -1);
            for (long[] item : mine) {
                int producer = (int) item[0];
                int seq = (int) item[1];
                assertThat(seen[producer][seq]).as("duplicate %s/%s", producer, seq).isFalse();
                seen[producer][seq] = true;
                // A single consumer takes items in channel order, so it sees each producer's items in order.
                assertThat(seq).isGreaterThan(lastSeq[producer]);
                lastSeq[producer] = seq;
                total++;
            }
        }
        assertThat(total).isEqualTo(producers * perProducer);
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private interface Blocking {
        void run() throws InterruptedException;
    }

    private static void uninterruptibly(Blocking body) {
        try {
            body.run();
        } catch (InterruptedException e) {
            throw new AssertionError("unexpected interrupt", e);
        }
    }

    private static void captureFailure(ConcurrentLinkedQueue<Throwable> errors, Blocking body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            errors.add(e);
        } catch (InterruptedException e) {
            throw new AssertionError("unexpected interrupt", e);
        }
    }

    /** Waits until the thread is parked on a lock or condition, i.e. really blocked inside the channel. */
    private static void awaitWaiting(Thread t) throws InterruptedException {
        while (t.getState() != Thread.State.WAITING && t.getState() != Thread.State.TIMED_WAITING) {
            assertThat(t.isAlive()).as("thread finished instead of blocking").isTrue();
            Thread.sleep(1);
        }
    }
}
