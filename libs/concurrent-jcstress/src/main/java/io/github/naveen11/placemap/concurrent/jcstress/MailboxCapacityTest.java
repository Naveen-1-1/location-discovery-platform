package io.github.naveen11.placemap.concurrent.jcstress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import io.github.naveen11.placemap.concurrent.CoalescingMailbox;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;

/**
 * Two writers offer different keys to a mailbox with room for one, while a reader takes once. The mailbox must never
 * hold more than one key, and each key must be delivered, still queued, or covered by a RESYNC: when the mailbox
 * overflows it may drop values, but never without saying so.
 *
 * <p>r1: 1 if any writer saw more keys than the capacity. r2: 1 if both keys are accounted for. r3: 1 if a RESYNC
 * was delivered or is still queued.
 */
@JCStressTest
@Outcome(id = "0, 1, 0", expect = ACCEPTABLE, desc = "The reader made room in time; no overflow.")
@Outcome(id = "0, 1, 1", expect = ACCEPTABLE, desc = "Overflow: values dropped and a RESYNC queued instead.")
@Outcome(id = "1, .*", expect = FORBIDDEN, desc = "More keys held than the capacity.")
@Outcome(id = "0, 0, .*", expect = FORBIDDEN, desc = "A key vanished without a RESYNC.")
@State
public class MailboxCapacityTest {

    static final int RESYNC = -1;

    final CoalescingMailbox<Integer, Integer> mailbox = new CoalescingMailbox<>(1, RESYNC);
    volatile boolean overCapacity;
    int polled; // written by the reader, read by the arbiter after every actor finished

    @Actor
    public void writerA() {
        mailbox.offer(1, 1);
        if (mailbox.size() > 1) {
            overCapacity = true;
        }
    }

    @Actor
    public void writerB() {
        mailbox.offer(2, 2);
        if (mailbox.size() > 1) {
            overCapacity = true;
        }
    }

    @Actor
    public void reader() {
        Integer value = mailbox.poll();
        polled = value == null ? 0 : value;
    }

    @Arbiter
    public void arbiter(III_Result r) {
        boolean seen1 = polled == 1;
        boolean seen2 = polled == 2;
        boolean resync = polled == RESYNC;
        Integer rest;
        while ((rest = mailbox.poll()) != null) {
            seen1 |= rest == 1;
            seen2 |= rest == 2;
            resync |= rest == RESYNC;
        }
        r.r1 = overCapacity ? 1 : 0;
        r.r2 = (seen1 && seen2) || resync ? 1 : 0;
        r.r3 = resync ? 1 : 0;
    }
}
