package io.github.naveen11.placemap.concurrent.jcstress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import io.github.naveen11.placemap.concurrent.BoundedChannel;
import io.github.naveen11.placemap.concurrent.ChannelFailedException;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * A put racing with fail and a waiting consumer. Fail may drop an accepted item (the batch is redone on resume),
 * but a put that reported failure must never be delivered, and the consumer must never mistake a failure for a
 * normal end of stream.
 *
 * <p>r1: put accepted (1) or refused as failed (2). r2: the consumer's take got the item (1), saw the failure (2), or
 * saw end of stream (0).
 */
@JCStressTest
@Outcome(id = "1, 1", expect = ACCEPTABLE, desc = "Put and take both happened before fail.")
@Outcome(id = "1, 2", expect = ACCEPTABLE, desc = "Put accepted, then fail dropped it before the take.")
@Outcome(id = "2, 2", expect = ACCEPTABLE, desc = "Fail came first: put refused, consumer saw the failure.")
@Outcome(id = "2, 1", expect = FORBIDDEN, desc = "Refused item was delivered anyway.")
@Outcome(expect = FORBIDDEN, desc = "Any other outcome, including a silent end of stream.")
@State
public class ChannelFailTest {

    private static final RuntimeException FAILURE = new RuntimeException("stage failed");

    final BoundedChannel<Integer> channel = new BoundedChannel<>(2);

    @Actor
    public void producer(II_Result r) {
        try {
            Uninterruptibly.put(channel, 1);
            r.r1 = 1;
        } catch (ChannelFailedException e) {
            r.r1 = 2;
        }
    }

    @Actor
    public void failer() {
        channel.fail(FAILURE);
    }

    @Actor
    public void consumer(II_Result r) {
        try {
            r.r2 = Uninterruptibly.take(channel) != null ? 1 : 0;
        } catch (ChannelFailedException e) {
            r.r2 = 2;
        }
    }
}
