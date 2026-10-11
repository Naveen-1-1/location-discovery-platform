package io.github.naveen11.placemap.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naveen11.placemap.concurrent.CoalescingMailbox.Offer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Behaviour of {@link CoalescingMailbox} one case at a time; Lincheck and JCStress cover the interleavings. */
@Timeout(10)
class CoalescingMailboxTest {

    private static final String RESYNC = "RESYNC";

    @Test
    void replacesAQueuedValueInPlaceSoTheKeyKeepsItsTurn() {
        var box = new CoalescingMailbox<String, String>(4, RESYNC);
        assertThat(box.offer("a", "a1")).isEqualTo(Offer.ADDED);
        assertThat(box.offer("b", "b1")).isEqualTo(Offer.ADDED);
        assertThat(box.offer("a", "a2")).isEqualTo(Offer.REPLACED);

        assertThat(drainAll(box)).containsExactly("a2", "b1");
        assertThat(box.isEmpty()).isTrue();
    }

    @Test
    void aKeyTakenOffTheQueueStartsAgainAtTheBack() {
        var box = new CoalescingMailbox<String, String>(4, RESYNC);
        box.offer("a", "a1");
        box.offer("b", "b1");
        assertThat(box.poll()).isEqualTo("a1");
        box.offer("a", "a2");
        assertThat(drainAll(box)).containsExactly("b1", "a2");
    }

    @Test
    void overflowClearsTheQueueAndDeliversResyncBeforeLaterValues() {
        var box = new CoalescingMailbox<String, String>(2, RESYNC);
        box.offer("a", "a1");
        box.offer("b", "b1");
        assertThat(box.offer("b", "b2")).as("a queued key never overflows").isEqualTo(Offer.REPLACED);
        assertThat(box.offer("c", "c1")).isEqualTo(Offer.OVERFLOWED);
        assertThat(box.size()).isZero();
        assertThat(box.isEmpty()).isFalse();

        box.offer("d", "d1");
        assertThat(drainAll(box)).containsExactly(RESYNC, "d1");
    }

    @Test
    void neverHoldsMoreThanItsCapacity() {
        var box = new CoalescingMailbox<Integer, Integer>(3, -1);
        for (int i = 0; i < 100; i++) {
            box.offer(i, i);
            assertThat(box.size()).isLessThanOrEqualTo(3);
        }
    }

    @Test
    void requestResyncDropsWhatWasQueuedAndHappensOnce() {
        var box = new CoalescingMailbox<String, String>(4, RESYNC);
        box.offer("a", "a1");
        assertThat(box.requestResync()).isTrue();
        assertThat(box.requestResync()).isTrue();
        box.offer("b", "b1");
        assertThat(drainAll(box)).containsExactly(RESYNC, "b1");
    }

    @Test
    void takeWaitsForAValue() throws Exception {
        var box = new CoalescingMailbox<String, String>(4, RESYNC);
        var taken = new CompletableFuture<String>();
        Thread consumer = Thread.ofPlatform().start(() -> {
            try {
                taken.complete(box.take());
            } catch (InterruptedException e) {
                taken.completeExceptionally(e);
            }
        });
        awaitWaiting(consumer);
        box.offer("a", "a1");
        assertThat(taken.get(5, TimeUnit.SECONDS)).isEqualTo("a1");
    }

    @Test
    void closeWakesTakeWithNullDropsEverythingAndRefusesOffers() throws Exception {
        var box = new CoalescingMailbox<String, String>(4, RESYNC);
        var taken = new CompletableFuture<String>();
        Thread consumer = Thread.ofPlatform().start(() -> {
            try {
                taken.complete(box.take());
            } catch (InterruptedException e) {
                taken.completeExceptionally(e);
            }
        });
        awaitWaiting(consumer);

        assertThat(box.close()).isTrue();
        assertThat(taken.get(5, TimeUnit.SECONDS)).isNull();
        assertThat(box.close()).as("only the first close reports true").isFalse();
        assertThat(box.offer("a", "a1")).isEqualTo(Offer.CLOSED);
        assertThat(box.requestResync()).isFalse();
        assertThat(box.poll()).isNull();
        assertThat(box.take()).isNull();
        assertThat(box.isClosed()).isTrue();
    }

    @Test
    void closeDropsQueuedValues() {
        var box = new CoalescingMailbox<String, String>(4, RESYNC);
        box.offer("a", "a1");
        box.requestResync();
        box.close();
        assertThat(box.size()).isZero();
        assertThat(box.isEmpty()).isTrue();
        assertThat(drainAll(box)).isEmpty();
    }

    @Test
    void takeCanBeInterrupted() throws Exception {
        var box = new CoalescingMailbox<String, String>(4, RESYNC);
        var outcome = new CompletableFuture<Throwable>();
        Thread consumer = Thread.ofPlatform().start(() -> {
            try {
                box.take();
                outcome.complete(null);
            } catch (InterruptedException e) {
                outcome.complete(e);
            }
        });
        awaitWaiting(consumer);
        consumer.interrupt();
        assertThat(outcome.get(5, TimeUnit.SECONDS)).isInstanceOf(InterruptedException.class);
    }

    @Test
    void drainToStopsAtMaxAndKeepsTheRest() {
        var box = new CoalescingMailbox<Integer, Integer>(8, -1);
        for (int i = 1; i <= 5; i++) {
            box.offer(i, i);
        }
        var sink = new ArrayList<Integer>();
        assertThat(box.drainTo(sink, 3)).isEqualTo(3);
        assertThat(sink).containsExactly(1, 2, 3);
        assertThat(box.size()).isEqualTo(2);
    }

    @Test
    void oldestWaitCountsFromWhenTheOldestKeyFirstStartedWaiting() {
        var clock = new AtomicLong(1_000);
        var box = new CoalescingMailbox<String, String>(4, RESYNC, clock::get);
        assertThat(box.oldestWaitNanos()).isZero();

        box.offer("a", "a1");
        clock.set(1_100);
        box.offer("b", "b1");
        box.offer("a", "a2"); // replacing keeps a's original start
        clock.set(1_500);
        assertThat(box.oldestWaitNanos()).isEqualTo(500);

        box.poll();
        assertThat(box.oldestWaitNanos()).isEqualTo(400); // b has waited since 1_100

        box.requestResync();
        clock.set(1_800);
        assertThat(box.oldestWaitNanos()).isEqualTo(300); // the resync replaced b

        box.poll();
        assertThat(box.oldestWaitNanos()).isZero();
    }

    @Test
    void rejectsBadArguments() {
        assertThatThrownBy(() -> new CoalescingMailbox<String, String>(0, RESYNC))
                .isInstanceOf(IllegalArgumentException.class);
        var box = new CoalescingMailbox<String, String>(1, RESYNC);
        assertThatThrownBy(() -> box.offer(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> box.offer("x", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aFastProducerAndASlowConsumerAlwaysEndOnTheNewestValues() throws Exception {
        var box = new CoalescingMailbox<Integer, Integer>(16, -1);
        int keys = 8;
        int rounds = 50_000;
        var lastSeen = new int[keys];
        var resyncs = new AtomicLong();
        var outOfOrder = new AtomicLong();
        Thread consumer = Thread.ofPlatform().start(() -> {
            try {
                Integer v;
                while ((v = box.take()) != null) {
                    if (v == -1) {
                        resyncs.incrementAndGet();
                        continue;
                    }
                    int key = v % keys;
                    int round = v / keys;
                    if (round <= lastSeen[key]) {
                        outOfOrder.incrementAndGet();
                    }
                    lastSeen[key] = round;
                }
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });
        for (int round = 1; round <= rounds; round++) {
            for (int key = 0; key < keys; key++) {
                box.offer(key, round * keys + key);
            }
        }
        while (!box.isEmpty()) {
            Thread.onSpinWait();
        }
        box.close();
        consumer.join();
        assertThat(outOfOrder.get()).as("values for one key arrive in order").isZero();
        for (int key = 0; key < keys; key++) {
            assertThat(lastSeen[key]).as("key %d ends on its newest value", key).isEqualTo(rounds);
        }
        assertThat(resyncs.get()).as("capacity 16 never fills with 8 keys").isZero();
    }

    private static <V> List<V> drainAll(CoalescingMailbox<?, V> box) {
        var out = new ArrayList<V>();
        box.drainTo(out, Integer.MAX_VALUE);
        return out;
    }

    /** Waits until the thread is parked on a lock or condition, i.e. really blocked inside the mailbox. */
    private static void awaitWaiting(Thread t) throws InterruptedException {
        while (t.getState() != Thread.State.WAITING && t.getState() != Thread.State.TIMED_WAITING) {
            assertThat(t.isAlive()).as("thread finished instead of blocking").isTrue();
            Thread.sleep(1);
        }
    }
}
