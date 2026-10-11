package io.github.naveen11.placemap.concurrent.jcstress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import io.github.naveen11.placemap.concurrent.SerialDrain;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * Two producers each queue an item and signal. The drain runs inline on whichever thread wins the signal, so the
 * other producer's signal usually lands while it runs. That signal must not be lost (both items drained once every
 * actor has returned), and the drain must never run on two threads at once.
 *
 * <p>r1: items drained. r2: times the drain was found running twice.
 */
@JCStressTest
@Outcome(id = "2, 0", expect = ACCEPTABLE, desc = "Both items drained, one drain at a time.")
@Outcome(expect = FORBIDDEN, desc = "A signal was lost (an item left behind), or the drain ran twice at once.")
@State
public class SerialDrainSignalTest {

    final ConcurrentLinkedQueue<Integer> work = new ConcurrentLinkedQueue<>();
    final AtomicInteger drained = new AtomicInteger();
    final AtomicInteger running = new AtomicInteger();
    final AtomicInteger overlaps = new AtomicInteger();
    final SerialDrain drain = new SerialDrain(Runnable::run, this::drainAll, () -> !work.isEmpty());

    void drainAll() {
        if (running.incrementAndGet() > 1) {
            overlaps.incrementAndGet();
        }
        while (work.poll() != null) {
            drained.incrementAndGet();
        }
        running.decrementAndGet();
    }

    @Actor
    public void producer1() {
        work.add(1);
        drain.signal();
    }

    @Actor
    public void producer2() {
        work.add(2);
        drain.signal();
    }

    @Arbiter
    public void arbiter(II_Result r) {
        r.r1 = drained.get();
        r.r2 = overlaps.get();
    }
}
