package io.github.naveen11.placemap.concurrent.jcstress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE_INTERESTING;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

/**
 * Phase 0 toolchain gate (demonstration): two threads run {@code x++} on a plain field. The read-modify-write is not
 * atomic, so one increment can be lost. JCStress should actually observe the "1" outcome on most hardware; it is
 * marked interesting rather than forbidden because this test documents the race instead of guarding code.
 */
@JCStressTest
@Outcome(id = "2", expect = ACCEPTABLE, desc = "Both increments landed.")
@Outcome(id = "1", expect = ACCEPTABLE_INTERESTING, desc = "Lost update: both actors read 0 and wrote 1.")
@State
public class PlainIncrementRace {

    int x;

    @Actor
    public void actor1() {
        x++;
    }

    @Actor
    public void actor2() {
        x++;
    }

    @Arbiter
    public void arbiter(I_Result r) {
        r.r1 = x;
    }
}
