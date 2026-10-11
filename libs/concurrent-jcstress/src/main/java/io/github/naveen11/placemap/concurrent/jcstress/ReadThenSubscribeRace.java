package io.github.naveen11.placemap.concurrent.jcstress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE_INTERESTING;

import io.github.naveen11.placemap.concurrent.CoalescingMailbox;
import io.github.naveen11.placemap.concurrent.SnapshotPublisher;
import java.util.Map;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * Demonstration (like {@link PlainIncrementRace}): {@link SnapshotSubscribeTest} with the two connect steps swapped,
 * reading the snapshot before subscribing. If the publish lands between the two steps, the client holds the old
 * snapshot and was not yet subscribed when the change went out, so it stays stale until the next change for that
 * key. Marked interesting rather than forbidden because it documents why live-hub subscribes first.
 *
 * <p>r1: version of the snapshot the client read. r2: the client's final value.
 */
@JCStressTest
@Outcome(id = {"0, 1", "1, 1"}, expect = ACCEPTABLE, desc = "The client ended on the final state.")
@Outcome(id = "0, 0", expect = ACCEPTABLE_INTERESTING, desc = "Stale: old snapshot, and the change was missed.")
@State
public class ReadThenSubscribeRace {

    record Snap(long version, int value) {
    }

    record Change(long version, int value) {
    }

    final SnapshotPublisher<Snap, String, Change> publisher = new SnapshotPublisher<>(new Snap(0, 0), Snap::version);
    final CoalescingMailbox<String, Change> mailbox = new CoalescingMailbox<>(4, new Change(-1, -1));
    long loadedVersion;
    int value;

    @Actor
    public void writer() {
        publisher.publish(new Snap(1, 1), Map.of("dock", new Change(1, 1)));
    }

    @Actor
    public void connect(II_Result r) {
        Snap snap = publisher.current(); // wrong order: read first...
        publisher.subscribe(mailbox::offer); // ...subscribe second
        loadedVersion = snap.version();
        value = snap.value();
        r.r1 = (int) snap.version();
    }

    @Arbiter
    public void arbiter(II_Result r) {
        Change change;
        while ((change = mailbox.poll()) != null) {
            if (change.version() > loadedVersion) {
                value = change.value();
            }
        }
        r.r2 = value;
    }
}
