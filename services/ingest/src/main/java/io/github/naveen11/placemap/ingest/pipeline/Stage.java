package io.github.naveen11.placemap.ingest.pipeline;

import io.github.naveen11.placemap.concurrent.BoundedChannel;
import io.github.naveen11.placemap.concurrent.ChannelFailedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * N worker threads between an input and an output channel. Each worker takes an item, processes
 * it and puts the result downstream, until the input reaches end of stream.
 *
 * <ul>
 *   <li><b>Normal end:</b> the last worker to finish closes the output, so end of stream flows down the pipeline only
 *       after every item this stage accepted has been passed on.
 *   <li><b>Failure:</b> the first error is recorded and both channels are failed, which stops the stages upstream
 *       (their puts throw) and downstream (their takes throw).
 *   <li><b>State per worker:</b> each thread gets its own {@link Worker} from the factory (a JDBC connection, a
 *       coordinate transform), so workers never share mutable objects.
 * </ul>
 */
public final class Stage<I, O> {

    /** Processes items on one thread. Closed when its thread ends; closing must not throw. */
    public interface Worker<I, O> extends AutoCloseable {

        /** @return the item to pass downstream, or null to pass nothing (a sink always returns null) */
        O process(I item) throws Exception;

        @Override
        default void close() {
        }
    }

    private final String name;
    private final int workers;
    private final ThreadFactory threads;
    private final BoundedChannel<I> in;
    private final BoundedChannel<O> out; // null for a sink
    private final Supplier<? extends Worker<I, O>> workerFactory;
    private final StageListener listener;
    private final AtomicReference<Throwable> pipelineFailure;
    private final AtomicInteger running = new AtomicInteger();
    private final List<Thread> started = new ArrayList<>();

    Stage(String name, int workers, ThreadFactory threads, BoundedChannel<I> in, BoundedChannel<O> out,
            Supplier<? extends Worker<I, O>> workerFactory, StageListener listener,
            AtomicReference<Throwable> pipelineFailure) {
        if (workers < 1) {
            throw new IllegalArgumentException("stage " + name + " needs at least one worker");
        }
        this.name = name;
        this.workers = workers;
        this.threads = threads;
        this.in = in;
        this.out = out;
        this.workerFactory = workerFactory;
        this.listener = listener;
        this.pipelineFailure = pipelineFailure;
    }

    void start() {
        running.set(workers);
        for (int i = 0; i < workers; i++) {
            Thread thread = threads.newThread(this::runWorker);
            started.add(thread);
            thread.start();
        }
    }

    private void runWorker() {
        try (Worker<I, O> worker = workerFactory.get()) {
            for (I item; (item = in.take()) != null; ) {
                long start = System.nanoTime();
                O result = worker.process(item);
                listener.processed(System.nanoTime() - start);
                if (result != null && out != null) {
                    long putStart = System.nanoTime();
                    out.put(result);
                    listener.blocked(System.nanoTime() - putStart);
                }
            }
        } catch (Throwable t) {
            // A failed channel carries the first failure as its cause; keep passing that one along.
            Throwable cause = t instanceof ChannelFailedException failed ? failed.getCause() : t;
            pipelineFailure.compareAndSet(null, new StageFailedException(name, cause));
            in.fail(cause);
            if (out != null) {
                out.fail(cause);
            }
        } finally {
            if (running.decrementAndGet() == 0 && out != null) {
                out.close(); // no-op if the output already failed
            }
        }
    }

    void interrupt() {
        started.forEach(Thread::interrupt);
    }

    /** Waits for every worker until the deadline (System.nanoTime based); true if all have ended. */
    boolean join(long deadlineNanos) throws InterruptedException {
        for (Thread thread : started) {
            long left = deadlineNanos - System.nanoTime();
            if (left <= 0 || !thread.join(Duration.ofNanos(left))) {
                return false;
            }
        }
        return true;
    }

    String name() {
        return name;
    }
}
