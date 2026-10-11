package io.github.naveen11.placemap.concurrent.jcstress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import io.github.naveen11.placemap.concurrent.SerialDrain;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * The watchdog interrupts a drain while a pool thread runs it. The interrupt may reach the drain or arrive too late
 * and do nothing, but it must never stay set on the pool thread afterwards: the thread's next task belongs to another
 * connection, which would be killed by mistake.
 *
 * <p>r1: 1 if the drain saw the interrupt. r2: 1 if the interrupt was still set after the drain returned.
 */
@JCStressTest
@Outcome(id = {"0, 0", "1, 0"}, expect = ACCEPTABLE, desc = "Interrupt reached the drain, or came too late.")
@Outcome(id = ".*, 1", expect = FORBIDDEN, desc = "Interrupt leaked into the pool thread's next task.")
@State
public class SerialDrainInterruptTest {

    volatile boolean sawInterrupt;
    final SerialDrain drain = new SerialDrain(Runnable::run, this::write, () -> false);

    void write() {
        for (int i = 0; i < 50 && !Thread.currentThread().isInterrupted(); i++) {
            Thread.onSpinWait(); // a short write the interrupt can land in
        }
        sawInterrupt = Thread.currentThread().isInterrupted();
    }

    @Actor
    public void poolThread(II_Result r) {
        drain.signal(); // runs the drain inline, as a pool thread would
        r.r2 = Thread.interrupted() ? 1 : 0;
        r.r1 = sawInterrupt ? 1 : 0;
    }

    @Actor
    public void watchdog() {
        drain.interruptRunning();
    }
}
