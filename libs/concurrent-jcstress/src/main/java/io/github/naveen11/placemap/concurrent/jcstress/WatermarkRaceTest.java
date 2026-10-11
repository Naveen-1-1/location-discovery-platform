package io.github.naveen11.placemap.concurrent.jcstress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import io.github.naveen11.placemap.concurrent.ContiguousWatermark;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

/**
 * Batches 0, 1 and 2 finish at the same time. Each thread may see the others' batches as not yet done, so the advance
 * relies on helping: whoever looks last must carry the watermark to the end. A lost advance leaves it short.
 */
@JCStressTest
@Outcome(id = "2", expect = ACCEPTABLE, desc = "Watermark covers all three batches.")
@Outcome(expect = FORBIDDEN, desc = "An advance was lost.")
@State
public class WatermarkRaceTest {

    final ContiguousWatermark watermark = new ContiguousWatermark();

    @Actor
    public void done0() {
        watermark.markDone(0);
    }

    @Actor
    public void done1() {
        watermark.markDone(1);
    }

    @Actor
    public void done2() {
        watermark.markDone(2);
    }

    @Arbiter
    public void arbiter(I_Result r) {
        r.r1 = (int) watermark.current();
    }
}
