package io.github.naveen11.placemap.concurrent.jcstress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import io.github.naveen11.placemap.concurrent.SnapshotPublisher;
import java.util.Map;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;

/**
 * The writer publishes versions 1 and 2 while a reader reads the current snapshot twice. The reader must always see a
 * whole snapshot, never one with some fields from before construction, and never an older version after a newer one.
 * The snapshot's fields are deliberately not final, so only the publisher's single atomic hand-off keeps them whole.
 *
 * <p>r1, r3: versions read first and second. r2: 1 if both snapshots read were whole.
 */
@JCStressTest
@Outcome(id = {"0, 1, 0", "0, 1, 1", "0, 1, 2", "1, 1, 1", "1, 1, 2", "2, 1, 2"}, expect = ACCEPTABLE,
        desc = "Whole snapshots, versions never going backwards.")
@Outcome(expect = FORBIDDEN, desc = "A torn snapshot, or a version going backwards.")
@State
public class SnapshotOrderTest {

    static final class Snap {
        long version; // not final on purpose
        long docks;
        long bikes;

        Snap(long version) {
            this.version = version;
            this.docks = version * 10;
            this.bikes = version * 100;
        }

        boolean whole() {
            return docks == version * 10 && bikes == version * 100;
        }
    }

    final SnapshotPublisher<Snap, String, Integer> publisher = new SnapshotPublisher<>(new Snap(0), s -> s.version);

    @Actor
    public void writer() {
        publisher.publish(new Snap(1), Map.of());
        publisher.publish(new Snap(2), Map.of());
    }

    @Actor
    public void reader(III_Result r) {
        Snap first = publisher.current();
        Snap second = publisher.current();
        r.r1 = (int) first.version;
        r.r2 = first.whole() && second.whole() ? 1 : 0;
        r.r3 = (int) second.version;
    }
}
