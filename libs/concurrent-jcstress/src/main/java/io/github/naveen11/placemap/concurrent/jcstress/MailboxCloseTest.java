package io.github.naveen11.placemap.concurrent.jcstress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import io.github.naveen11.placemap.concurrent.CoalescingMailbox;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.IIII_Result;

/**
 * Two paths close the same mailbox at once (in live-hub: the watchdog evicting a stuck client while the client
 * disconnects) while the writer offers a value. Exactly one close may win, since live-hub runs a session's cleanup only
 * on the winning close; and a closed mailbox holds nothing, whether the offer landed before the close or not.
 *
 * <p>r1, r2: 1 if that close call won. r3: 1 if the offer was accepted. r4: values still held afterwards.
 */
@JCStressTest
@Outcome(id = {"1, 0, 0, 0", "1, 0, 1, 0", "0, 1, 0, 0", "0, 1, 1, 0"}, expect = ACCEPTABLE,
        desc = "Exactly one close won and the closed mailbox is empty.")
@Outcome(id = {"1, 1, .*", "0, 0, .*"}, expect = FORBIDDEN, desc = "Cleanup would run twice, or never.")
@Outcome(expect = FORBIDDEN, desc = "A closed mailbox still holds a value.")
@State
public class MailboxCloseTest {

    final CoalescingMailbox<String, Integer> mailbox = new CoalescingMailbox<>(4, -1);

    @Actor
    public void watchdog(IIII_Result r) {
        r.r1 = mailbox.close() ? 1 : 0;
    }

    @Actor
    public void disconnect(IIII_Result r) {
        r.r2 = mailbox.close() ? 1 : 0;
    }

    @Actor
    public void writer(IIII_Result r) {
        r.r3 = mailbox.offer("dock", 1) == CoalescingMailbox.Offer.ADDED ? 1 : 0;
    }

    @Arbiter
    public void arbiter(IIII_Result r) {
        r.r4 = mailbox.size() + (mailbox.isEmpty() ? 0 : 1);
    }
}
