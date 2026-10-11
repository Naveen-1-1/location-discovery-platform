package io.github.naveen11.placemap.concurrent.jcstress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import io.github.naveen11.placemap.concurrent.BoundedChannel;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * Two producers and two consumers through a one-slot channel, so one producer always has to wait for a consumer.
 * Each item must arrive exactly once; a lost wake-up would hang the test, which JCStress reports as an error.
 *
 * <p>r1, r2: the items the two consumers took.
 */
@JCStressTest
@Outcome(id = {"1, 2", "2, 1"}, expect = ACCEPTABLE, desc = "Each item delivered exactly once.")
@Outcome(expect = FORBIDDEN, desc = "An item lost or delivered twice.")
@State
public class ChannelHandoffTest {

    final BoundedChannel<Integer> channel = new BoundedChannel<>(1);

    @Actor
    public void producer1() {
        Uninterruptibly.put(channel, 1);
    }

    @Actor
    public void producer2() {
        Uninterruptibly.put(channel, 2);
    }

    @Actor
    public void consumer1(II_Result r) {
        r.r1 = Uninterruptibly.take(channel);
    }

    @Actor
    public void consumer2(II_Result r) {
        r.r2 = Uninterruptibly.take(channel);
    }
}
