package io.github.naveen11.placemap.concurrent.jcstress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import io.github.naveen11.placemap.concurrent.BoundedChannel;
import io.github.naveen11.placemap.concurrent.ChannelClosedException;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;

/**
 * A put racing with close and a waiting consumer. An item that was accepted before close must be delivered before
 * end of stream, and an item that was refused must never be delivered.
 *
 * <p>r1: put accepted (1) or refused as closed (0). r2: the consumer's take got the item (1) or end of stream (0).
 * r3: items still buffered afterwards.
 */
@JCStressTest
@Outcome(id = "1, 1, 0", expect = ACCEPTABLE, desc = "Put landed before close and was delivered.")
@Outcome(id = "0, 0, 0", expect = ACCEPTABLE, desc = "Close came first: put refused, consumer saw end of stream.")
@Outcome(id = "1, 0, 1", expect = FORBIDDEN, desc = "Accepted item stranded behind end of stream.")
@Outcome(id = "1, 0, 0", expect = FORBIDDEN, desc = "Accepted item lost.")
@Outcome(id = "0, 1, 0", expect = FORBIDDEN, desc = "Refused item was delivered anyway.")
@Outcome(expect = FORBIDDEN, desc = "Any other outcome.")
@State
public class ChannelCloseTest {

    final BoundedChannel<Integer> channel = new BoundedChannel<>(2); // never full here, so put never blocks

    @Actor
    public void producer(III_Result r) {
        try {
            Uninterruptibly.put(channel, 1);
            r.r1 = 1;
        } catch (ChannelClosedException e) {
            r.r1 = 0;
        }
    }

    @Actor
    public void closer() {
        channel.close();
    }

    @Actor
    public void consumer(III_Result r) {
        // Blocks until the put or the close; both always happen, so it cannot hang.
        r.r2 = Uninterruptibly.take(channel) != null ? 1 : 0;
    }

    @Arbiter
    public void arbiter(III_Result r) {
        r.r3 = channel.size();
    }
}
