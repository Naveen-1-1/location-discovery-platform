package io.github.naveen11.placemap.concurrent.jcstress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE_INTERESTING;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * Demonstration (like {@link PlainIncrementRace}): the {@link WatermarkJumpTest} scenario against the obvious design
 * that advances the watermark one batch per compare-and-set. A reader can land between two steps and see 0 or 1.
 * Marked interesting rather than forbidden because this documents why the real watermark jumps in one step. The
 * window is a single hash lookup wide, so the outcome is rare.
 */
@JCStressTest
@Outcome(id = {"-1, 2", "2, 2"}, expect = ACCEPTABLE, desc = "Reader saw the watermark before or after the advance.")
@Outcome(id = {"0, 2", "1, 2"}, expect = ACCEPTABLE_INTERESTING, desc = "Reader saw a value part-way through.")
@State
public class SteppingWatermarkRace {

    final AtomicLong watermark = new AtomicLong(-1);
    final Set<Long> pending = ConcurrentHashMap.newKeySet();

    public SteppingWatermarkRace() {
        pending.add(1L);
        pending.add(2L);
    }

    @Actor
    public void done0() {
        pending.add(0L);
        while (true) {
            long w = watermark.get();
            if (!pending.contains(w + 1)) {
                return;
            }
            watermark.compareAndSet(w, w + 1); // one batch at a time: each step is visible on its own
        }
    }

    @Actor
    public void reader(II_Result r) {
        r.r1 = (int) watermark.get();
    }

    @Arbiter
    public void arbiter(II_Result r) {
        r.r2 = (int) watermark.get();
    }
}
