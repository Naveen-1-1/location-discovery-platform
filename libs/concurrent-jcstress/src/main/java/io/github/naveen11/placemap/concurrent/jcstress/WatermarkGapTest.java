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
 * Batches 1 and 2 finish concurrently while batch 0 never does. The watermark must never pass the missing batch,
 * whether read during the race (r1) or after it (r2).
 */
@JCStressTest
@Outcome(id = "-1, -1", expect = ACCEPTABLE, desc = "Watermark held at the gap.")
@Outcome(expect = FORBIDDEN, desc = "Watermark passed a batch that is not done.")
@State
public class WatermarkGapTest {

    final ContiguousWatermark watermark = new ContiguousWatermark();

    @Actor
    public void done1() {
        watermark.markDone(1);
    }

    @Actor
    public void done2() {
        watermark.markDone(2);
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
