package io.github.naveen11.placemap.concurrent.jcstress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

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
 * A client connects while the writer publishes version 1. The client follows live-hub's connect steps: its mailbox
 * starts with a RESYNC, it subscribes, its drain takes the RESYNC and reads the current snapshot, and later changes not
 * newer than that snapshot are dropped. Whichever way the race goes, the client must end on the final state.
 *
 * <p>r1: version of the snapshot the client read. r2: the client's final value.
 */
@JCStressTest
@Outcome(id = "0, 1", expect = ACCEPTABLE, desc = "Read the old snapshot, then received the change.")
@Outcome(id = "1, 1", expect = ACCEPTABLE, desc = "Read the new snapshot; the change was dropped as not newer.")
@Outcome(expect = FORBIDDEN, desc = "The client missed the update.")
@State
public class SnapshotSubscribeTest {

    record Snap(long version, int value) {
    }

    record Change(long version, int value) {
    }

    static final Change RESYNC = new Change(-1, -1);

    final SnapshotPublisher<Snap, String, Change> publisher = new SnapshotPublisher<>(new Snap(0, 0), Snap::version);
    final CoalescingMailbox<String, Change> mailbox = new CoalescingMailbox<>(4, RESYNC);
    final SnapshotPublisher.Subscriber<String, Change> client = mailbox::offer;
    long loadedVersion;
    int value;

    public SnapshotSubscribeTest() {
        mailbox.requestResync();
    }

    @Actor
    public void writer() {
        publisher.publish(new Snap(1, 1), Map.of("dock", new Change(1, 1)));
    }

    @Actor
    public void connect(II_Result r) {
        publisher.subscribe(client);
        if (mailbox.poll() != RESYNC) {
            throw new IllegalStateException("a new client's first delivery is the RESYNC");
        }
        Snap snap = publisher.current();
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
