package io.github.naveen11.placemap.concurrent.jcstress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

/**
 * Phase 0 toolchain gate (guard): the same race as {@link PlainIncrementRace}, fixed with an atomic increment. A lost
 * update is now FORBIDDEN, so seeing "1" fails the run (and CI).
 */
@JCStressTest
@Outcome(id = "2", expect = ACCEPTABLE, desc = "Both increments landed.")
@Outcome(id = "1", expect = FORBIDDEN, desc = "Lost update must be impossible with AtomicInteger.")
@State
public class AtomicIncrementTest {

    final AtomicInteger x = new AtomicInteger();

    @Actor
    public void actor1() {
        x.incrementAndGet();
    }

    @Actor
    public void actor2() {
        x.incrementAndGet();
    }

    @Arbiter
    public void arbiter(I_Result r) {
        r.r1 = x.get();
    }
}
