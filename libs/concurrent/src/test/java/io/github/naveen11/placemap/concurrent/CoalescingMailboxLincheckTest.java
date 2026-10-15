package io.github.naveen11.placemap.concurrent;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.junit.jupiter.api.Test;

/**
 * Lincheck: every concurrent history of {@link CoalescingMailbox} must match some one-at-a-time order of the same
 * calls run against {@link Spec}, a plain {@link LinkedHashMap} model of the intended behaviour.
 *
 * <p>Only the non-blocking calls are exercised: a take on an empty mailbox parks, which Lincheck reports as a
 * deadlock. {@code take} shares {@code poll}'s locked state change and is covered
 * by {@link CoalescingMailboxTest}.
 */
@Param(name = "key", gen = IntGen.class, conf = "1:3")
@Param(name = "value", gen = IntGen.class, conf = "1:3")
public class CoalescingMailboxLincheckTest {

    static final int CAPACITY = 2;
    static final int RESYNC = 0;

    private final CoalescingMailbox<Integer, Integer> mailbox = new CoalescingMailbox<>(CAPACITY, RESYNC, () -> 0L);

    @Operation
    public CoalescingMailbox.Offer offer(@Param(name = "key") int key, @Param(name = "value") int value) {
        return mailbox.offer(key, value);
    }

    @Operation
    public Integer poll() {
        return mailbox.poll();
    }

    @Operation
    public boolean requestResync() {
        return mailbox.requestResync();
    }

    @Operation
    public boolean close() {
        return mailbox.close();
    }

    @Operation
    public int size() {
        return mailbox.size();
    }

    @Operation
    public boolean isEmpty() {
        return mailbox.isEmpty();
    }

    @Test
    void isLinearizable() {
        new ModelCheckingOptions()
                .iterations(20)
                .invocationsPerIteration(1_000)
                .sequentialSpecification(Spec.class)
                .check(CoalescingMailboxLincheckTest.class);
    }

    /** The intended behaviour as the simplest single-threaded code. */
    public static class Spec {
        private final LinkedHashMap<Integer, Integer> queued = new LinkedHashMap<>();
        private boolean resync;
        private boolean closed;

        public CoalescingMailbox.Offer offer(int key, int value) {
            if (closed) {
                return CoalescingMailbox.Offer.CLOSED;
            }
            if (queued.containsKey(key)) {
                queued.put(key, value); // an existing key keeps its place in a LinkedHashMap
                return CoalescingMailbox.Offer.REPLACED;
            }
            if (queued.size() == CAPACITY) {
                queued.clear();
                resync = true;
                return CoalescingMailbox.Offer.OVERFLOWED;
            }
            queued.put(key, value);
            return CoalescingMailbox.Offer.ADDED;
        }

        public Integer poll() {
            if (closed) {
                return null;
            }
            if (resync) {
                resync = false;
                return RESYNC;
            }
            Map.Entry<Integer, Integer> first = queued.pollFirstEntry();
            return first == null ? null : first.getValue();
        }

        public boolean requestResync() {
            if (closed) {
                return false;
            }
            queued.clear();
            resync = true;
            return true;
        }

        public boolean close() {
            if (closed) {
                return false;
            }
            closed = true;
            queued.clear();
            resync = false;
            return true;
        }

        public int size() {
            return queued.size();
        }

        public boolean isEmpty() {
            return !resync && queued.isEmpty();
        }
    }
}
