package io.github.naveen11.placemap.concurrent;

import java.util.HashSet;
import java.util.Set;
import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.junit.jupiter.api.Test;

/**
 * Lincheck: every concurrent history of {@link ContiguousWatermark} must match some one-at-a-time order of the same
 * calls run against {@link Spec}. This is what rules out a reader seeing a value part-way through an advance (with 1
 * and 2 done, {@code current()} during {@code markDone(0)} may return -1 or 2, never 0 or 1), and a reader seeing a
 * watermark below a batch whose {@code markDone} has already returned.
 */
@Param(name = "seq", gen = IntGen.class, conf = "0:4")
public class ContiguousWatermarkLincheckTest {

    private final ContiguousWatermark watermark = new ContiguousWatermark();

    @Operation
    public void markDone(@Param(name = "seq") int seq) {
        watermark.markDone(seq);
    }

    @Operation
    public long current() {
        return watermark.current();
    }

    @Test
    void isLinearizable() {
        // Same budget as the Phase 0 positive control: model checking also walks ConcurrentHashMap's internals.
        new ModelCheckingOptions()
                .iterations(20)
                .invocationsPerIteration(1_000)
                .sequentialSpecification(Spec.class)
                .check(ContiguousWatermarkLincheckTest.class);
    }

    /** The intended behaviour as the simplest single-threaded code. */
    public static class Spec {
        private final Set<Integer> done = new HashSet<>();

        public void markDone(int seq) {
            done.add(seq);
        }

        public long current() {
            long w = -1;
            while (done.contains((int) w + 1)) {
                w++;
            }
            return w;
        }
    }
}
