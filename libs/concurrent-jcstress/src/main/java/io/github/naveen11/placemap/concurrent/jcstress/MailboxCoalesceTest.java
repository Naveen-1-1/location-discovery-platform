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
 * The writer offers v1 then v2 for one key while a reader takes twice. The reader must never see v1 after v2 or the
 * same value twice, and v2 must end up delivered or still queued: the newest value is never lost.
 *
 * <p>r1, r2: the reader's two polls (0 = nothing waiting). r3: what is left in the mailbox afterwards.
 */
@JCStressTest
@Outcome(id = {"0, 0, 2", "0, 1, 2", "0, 2, 0", "1, 0, 2", "1, 2, 0", "2, 0, 0"}, expect = ACCEPTABLE,
        desc = "Values arrive in order, possibly coalesced, and v2 is delivered or still queued.")
@Outcome(expect = FORBIDDEN, desc = "Reordered, duplicated, or the newest value lost.")
@State
public class MailboxCoalesceTest {

    final CoalescingMailbox<String, Integer> mailbox = new CoalescingMailbox<>(4, -1);

    @Actor
    public void writer() {
        mailbox.offer("dock", 1);
        mailbox.offer("dock", 2);
    }

    @Actor
    public void reader(III_Result r) {
        r.r1 = orZero(mailbox.poll());
        r.r2 = orZero(mailbox.poll());
    }

    @Arbiter
    public void arbiter(III_Result r) {
        r.r3 = orZero(mailbox.poll());
    }

    static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
