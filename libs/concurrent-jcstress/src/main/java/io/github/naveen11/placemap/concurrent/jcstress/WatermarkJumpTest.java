package io.github.naveen11.placemap.concurrent.jcstress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import io.github.naveen11.placemap.concurrent.ContiguousWatermark;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * Batches 1 and 2 are already done; batch 0 finishes while a reader looks (r1). The watermark must jump from -1
 * straight to 2: a reader seeing 0 or 1 would see a value that was never the true "all done up to N".
 * {@link SteppingWatermarkRace} shows the same scenario with a one-step-at-a-time advance.
 */
@JCStressTest
@Outcome(id = {"-1, 2", "2, 2"}, expect = ACCEPTABLE, desc = "Reader saw the watermark before or after the jump.")
@Outcome(id = {"0, 2", "1, 2"}, expect = FORBIDDEN, desc = "Reader saw a value part-way through the advance.")
@Outcome(expect = FORBIDDEN, desc = "Any other outcome.")
@State
public class WatermarkJumpTest {

    final ContiguousWatermark watermark = new ContiguousWatermark();

    public WatermarkJumpTest() {
        watermark.markDone(1);
        watermark.markDone(2);
    }

    @Actor
    public void done0() {
        watermark.markDone(0);
    }

    @Actor
    public void reader(II_Result r) {
        r.r1 = (int) watermark.current();
    }

    @Arbiter
    public void arbiter(II_Result r) {
        r.r2 = (int) watermark.current();
    }
}
