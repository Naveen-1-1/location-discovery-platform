package io.github.naveen11.placemap.ingest.pipeline;

import io.github.naveen11.placemap.concurrent.BoundedChannel;
import io.github.naveen11.placemap.concurrent.ChannelFailedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * One source thread feeding a chain of {@link Stage}s through bounded channels (ADR 0021, 0036).
 *
 * <ul>
 *   <li>{@link #requestStop()} is the graceful path (SIGTERM): the source stops between items and closes its channel;
 *       every stage then drains what it already holds and closes its own output.
 *   <li>{@link #cancel(Throwable)} is the hard path: every channel fails and every thread is interrupted. Work in
 *       flight is abandoned; for the importer that means an uncommitted batch, redone on resume.
 * </ul>
 */
public final class Pipeline {

    /** Fills the first channel. Returning normally closes it (end of stream); throwing fails it. */
    @FunctionalInterface
    public interface Source<T> {
        void run(BoundedChannel<T> out, BooleanSupplier stopRequested) throws Exception;
    }

    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final List<BoundedChannel<?>> channels = new ArrayList<>();
    private final List<Stage<?, ?>> stages = new ArrayList<>();
    private Thread sourceThread;
    private volatile boolean stopRequested;

    public <T> void source(String name, ThreadFactory threads, BoundedChannel<T> out, Source<T> source) {
        if (sourceThread != null) {
            throw new IllegalStateException("a pipeline has exactly one source");
        }
        channels.add(out);
        sourceThread = threads.newThread(() -> {
            try {
                source.run(out, () -> stopRequested);
                out.close();
            } catch (Throwable t) {
                Throwable cause = t instanceof ChannelFailedException failed ? failed.getCause() : t;
                failure.compareAndSet(null, new StageFailedException(name, cause));
                out.fail(cause);
            }
        });
    }

    public <I, O> void stage(String name, int workers, ThreadFactory threads, BoundedChannel<I> in,
            BoundedChannel<O> out, Supplier<? extends Stage.Worker<I, O>> workerFactory, StageListener listener) {
        channels.add(out);
        stages.add(new Stage<>(name, workers, threads, in, out, workerFactory, listener, failure));
    }

    public <I> void sink(String name, int workers, ThreadFactory threads, BoundedChannel<I> in,
            Supplier<? extends Stage.Worker<I, Void>> workerFactory, StageListener listener) {
        stages.add(new Stage<>(name, workers, threads, in, null, workerFactory, listener, failure));
    }

    public void start() {
        if (sourceThread == null) {
            throw new IllegalStateException("pipeline has no source");
        }
        stages.forEach(Stage::start); // consumers first, so the source never waits on a stage that is not running
        sourceThread.start();
    }

    public void requestStop() {
        stopRequested = true;
    }

    public boolean stopRequested() {
        return stopRequested;
    }

    public void cancel(Throwable cause) {
        failure.compareAndSet(null, cause);
        channels.forEach(channel -> channel.fail(cause));
        sourceThread.interrupt();
        stages.forEach(Stage::interrupt);
    }

    /** Waits until the source and every stage have finished; true if they did within the timeout. */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        long left = deadline - System.nanoTime();
        if (left <= 0 || !sourceThread.join(Duration.ofNanos(left))) {
            return false;
        }
        for (Stage<?, ?> stage : stages) {
            if (!stage.join(deadline)) {
                return false;
            }
        }
        return true;
    }

    /** The first error in any stage, if one failed. */
    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure.get());
    }
}
