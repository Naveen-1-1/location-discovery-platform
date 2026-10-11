package io.github.naveen11.placemap.ingest.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.concurrent.BoundedChannel;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** End of stream, failure propagation, graceful stop, cancellation and backpressure through a real pipeline. */
@Timeout(20)
class PipelineTest {

    private static final ThreadFactory THREADS = Thread.ofPlatform().name("test-", 1).factory();

    /** source → square (3 workers) → collect (2 workers). */
    private static Pipeline squares(int count, ConcurrentLinkedQueue<Integer> sink, Stage.Worker<Integer, Integer> square,
            StageListener squareListener) {
        var numbers = new BoundedChannel<Integer>(2);
        var squared = new BoundedChannel<Integer>(2);
        var pipeline = new Pipeline();
        pipeline.source("numbers", THREADS, numbers, (out, stop) -> {
            for (int i = 1; i <= count && !stop.getAsBoolean(); i++) {
                out.put(i);
            }
        });
        pipeline.stage("square", 3, THREADS, numbers, squared, () -> square, squareListener);
        pipeline.sink("collect", 2, THREADS, squared, () -> item -> {
            sink.add(item);
            return null;
        }, StageListener.NONE);
        return pipeline;
    }

    @Test
    void everyItemArrivesAndEndOfStreamEndsEveryStage() throws Exception {
        var sink = new ConcurrentLinkedQueue<Integer>();
        Pipeline pipeline = squares(1_000, sink, i -> i * i, StageListener.NONE);

        pipeline.start();

        assertThat(pipeline.awaitTermination(Duration.ofSeconds(10))).isTrue();
        assertThat(pipeline.failure()).isEmpty();
        assertThat(sink).containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, 1_000).map(i -> i * i).boxed().toList());
    }

    @Test
    void aFailureInOneStageStopsTheWholePipelineAndNamesTheStage() throws Exception {
        var sink = new ConcurrentLinkedQueue<Integer>();
        Pipeline pipeline = squares(1_000_000, sink, i -> {
            if (i == 50) {
                throw new IllegalStateException("bad item 50");
            }
            return i * i;
        }, StageListener.NONE);

        pipeline.start();

        // The source would take far longer than the timeout to produce a million items: it only ends this fast
        // because its put fails once the stage downstream has failed the channel.
        assertThat(pipeline.awaitTermination(Duration.ofSeconds(10))).isTrue();
        assertThat(pipeline.failure().orElseThrow())
                .isInstanceOf(StageFailedException.class)
                .satisfies(e -> assertThat(((StageFailedException) e).stage()).isEqualTo("square"))
                .hasRootCauseMessage("bad item 50");
        assertThat(sink.size()).isLessThan(1_000);
    }

    @Test
    void requestStopDrainsWhatIsInFlightWithoutFailing() throws Exception {
        var sink = new ConcurrentLinkedQueue<Integer>();
        var seen = new AtomicInteger();
        var tenSeen = new CountDownLatch(10);
        Pipeline pipeline = squares(1_000_000, sink, i -> {
            seen.incrementAndGet();
            tenSeen.countDown();
            return i;
        }, StageListener.NONE);

        pipeline.start();
        tenSeen.await();
        pipeline.requestStop();

        assertThat(pipeline.awaitTermination(Duration.ofSeconds(10))).isTrue();
        assertThat(pipeline.failure()).isEmpty();
        assertThat(sink).hasSize(seen.get()); // everything a stage accepted was passed on
        assertThat(sink.size()).isLessThan(1_000_000);
    }

    @Test
    void cancelInterruptsWorkersThatAreStuck() throws Exception {
        var sink = new ConcurrentLinkedQueue<Integer>();
        var stuck = new CountDownLatch(1);
        Pipeline pipeline = squares(10, sink, i -> {
            stuck.countDown();
            Thread.sleep(60_000); // a slow database call
            return i;
        }, StageListener.NONE);

        pipeline.start();
        stuck.await();
        pipeline.cancel(new RuntimeException("shutting down"));

        assertThat(pipeline.awaitTermination(Duration.ofSeconds(5))).isTrue();
        assertThat(pipeline.failure().orElseThrow()).hasMessage("shutting down");
    }

    @Test
    void aSlowConsumerMakesTheStagesUpstreamWait() throws Exception {
        var blockedNanos = new AtomicLong();
        var numbers = new BoundedChannel<Integer>(2);
        var squared = new BoundedChannel<Integer>(2);
        var maxQueued = new AtomicInteger();
        var pipeline = new Pipeline();
        pipeline.source("numbers", THREADS, numbers, (out, stop) -> {
            for (int i = 0; i < 40; i++) {
                out.put(i);
            }
        });
        pipeline.stage("pass", 1, THREADS, numbers, squared, () -> i -> i, new StageListener() {
            @Override
            public void blocked(long nanos) {
                blockedNanos.addAndGet(nanos);
            }
        });
        pipeline.sink("slow", 1, THREADS, squared, () -> i -> {
            maxQueued.accumulateAndGet(numbers.size() + squared.size(), Math::max);
            Thread.sleep(5);
            return null;
        }, StageListener.NONE);

        pipeline.start();

        assertThat(pipeline.awaitTermination(Duration.ofSeconds(10))).isTrue();
        assertThat(maxQueued.get()).isLessThanOrEqualTo(4); // never more than the two capacities
        assertThat(Duration.ofNanos(blockedNanos.get())).isGreaterThan(Duration.ofMillis(50));
    }

    @Test
    void eachWorkerGetsItsOwnInstance() throws Exception {
        var instances = new ConcurrentLinkedQueue<Object>();
        var in = new BoundedChannel<Integer>(4);
        var pipeline = new Pipeline();
        pipeline.source("numbers", THREADS, in, (out, stop) -> {
            for (int i = 0; i < 100; i++) {
                out.put(i);
            }
        });
        pipeline.sink("count", 4, THREADS, in, () -> {
            var worker = new Stage.Worker<Integer, Void>() {
                @Override
                public Void process(Integer item) {
                    return null;
                }
            };
            instances.add(worker);
            return worker;
        }, StageListener.NONE);

        pipeline.start();

        assertThat(pipeline.awaitTermination(Duration.ofSeconds(10))).isTrue();
        assertThat(List.copyOf(instances)).hasSize(4).doesNotHaveDuplicates();
    }
}
