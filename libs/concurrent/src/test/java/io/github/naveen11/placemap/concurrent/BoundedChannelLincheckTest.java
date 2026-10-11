package io.github.naveen11.placemap.concurrent;

import java.util.ArrayDeque;
import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.junit.jupiter.api.Test;

/**
 * Lincheck: every concurrent history of {@link BoundedChannel} must match some one-at-a-time order of the same calls
 * run against {@link Spec}, a plain single-threaded model of the intended behaviour.
 *
 * <p>Only the non-blocking calls are exercised: Lincheck reports a call that parks forever (a take on an empty open
 * channel) as a deadlock. {@code put} and {@code take} share the same locked state changes and are covered by
 * {@link BoundedChannelTest} and the JCStress tests.
 */
@Param(name = "value", gen = IntGen.class, conf = "1:3")
public class BoundedChannelLincheckTest {

    static final int CAPACITY = 2;
    private static final RuntimeException FAILURE = new RuntimeException("stage failed");

    private final BoundedChannel<Integer> channel = new BoundedChannel<>(CAPACITY);

    @Operation
    public String offer(@Param(name = "value") int value) {
        try {
            return channel.offer(value) ? "accepted" : "full";
        } catch (ChannelClosedException e) {
            return "closed";
        } catch (ChannelFailedException e) {
            return "failed";
        }
    }

    @Operation
    public String poll() {
        try {
            Integer item = channel.poll();
            return item == null ? "empty" : item.toString();
        } catch (ChannelFailedException e) {
            return "failed";
        }
    }

    @Operation
    public void close() {
        channel.close();
    }

    @Operation
    public void fail() {
        channel.fail(FAILURE);
    }

    @Operation
    public int size() {
        return channel.size();
    }

    @Test
    void isLinearizable() {
        // Same budget as the Phase 0 positive control: model checking also walks ReentrantLock's internals.
        new ModelCheckingOptions()
                .iterations(20)
                .invocationsPerIteration(1_000)
                .sequentialSpecification(Spec.class)
                .check(BoundedChannelLincheckTest.class);
    }

    /** The intended behaviour as the simplest single-threaded code. */
    public static class Spec {
        private final ArrayDeque<Integer> items = new ArrayDeque<>();
        private boolean closed;
        private boolean failed;

        public String offer(int value) {
            if (failed) {
                return "failed";
            }
            if (closed) {
                return "closed";
            }
            if (items.size() == CAPACITY) {
                return "full";
            }
            items.add(value);
            return "accepted";
        }

        public String poll() {
            if (failed) {
                return "failed";
            }
            Integer item = items.poll();
            return item == null ? "empty" : item.toString();
        }

        public void close() {
            closed = true;
        }

        public void fail() {
            failed = true;
            items.clear();
        }

        public int size() {
            return items.size();
        }
    }
}
