package io.github.naveen11.placemap.concurrent;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * Runs one subscriber's drain task on a shared executor, never two at once and never missing a signal.
 *
 * <p>This is how a fixed pool of threads serves many more connections than it has threads: a connection with news
 * calls {@link #signal()}, a free pool thread runs its drain task, which writes what is waiting and returns. A
 * connection costs a thread only while it is being written to. (With a thread per connection the drain is a plain
 * loop instead, and this class is not needed.)
 *
 * <ul>
 *   <li><b>One at a time.</b> A flag is set with compare-and-set before the task is submitted and cleared after it
 *       finishes, so a second signal while it is queued or running does not submit it again.
 *   <li><b>No lost signal.</b> A signal that arrives while the task runs finds the flag set and returns. So after
 *       clearing the flag the task checks {@code hasWork} once more and resubmits itself. Resubmitting, rather than
 *       looping on the same thread, lets other connections' tasks take their turn.
 *   <li><b>Interrupts stay inside the task.</b> {@link #interruptRunning()} interrupts the thread only while it runs
 *       this task, and the task clears any interrupt before returning. A pool thread therefore never carries an
 *       eviction meant for one connection into the next connection's task.
 * </ul>
 *
 * <p>The JCStress tests in {@code libs/concurrent-jcstress} check the last two points.
 */
public final class SerialDrain {

    private final Executor executor;
    private final Runnable task;
    private final BooleanSupplier hasWork;
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final ReentrantLock runnerLock = new ReentrantLock();
    private Thread runner; // guarded by runnerLock; the thread running the task right now, if any

    /**
     * @param executor where the task runs, typically a fixed pool shared by many subscribers
     * @param task     drains what is waiting and returns; it must not wait for new work
     * @param hasWork  whether the task should run again; read after each run to catch a signal that arrived during it
     */
    public SerialDrain(Executor executor, Runnable task, BooleanSupplier hasWork) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.task = Objects.requireNonNull(task, "task");
        this.hasWork = Objects.requireNonNull(hasWork, "hasWork");
    }

    /**
     * Makes sure the task runs at least once after this call. Never blocks.
     *
     * @throws RejectedExecutionException if the executor refuses the task (it is shutting down)
     */
    public void signal() {
        if (scheduled.compareAndSet(false, true)) {
            try {
                executor.execute(this::run);
            } catch (RejectedExecutionException e) {
                scheduled.set(false);
                throw e;
            }
        }
    }

    /** Interrupts the thread running the task, if it is running now; otherwise does nothing. */
    public void interruptRunning() {
        runnerLock.lock();
        try {
            if (runner != null) {
                runner.interrupt();
            }
        } finally {
            runnerLock.unlock();
        }
    }

    private void run() {
        setRunner(Thread.currentThread());
        try {
            task.run();
        } finally {
            runnerLock.lock();
            try {
                runner = null;
                // Under the same lock as interruptRunning(), so an interrupt meant for this task either landed
                // already (and is cleared here) or will not be sent at all.
                Thread.interrupted();
            } finally {
                runnerLock.unlock();
            }
            scheduled.set(false);
            if (hasWork.getAsBoolean()) {
                try {
                    signal();
                } catch (RejectedExecutionException e) {
                    // The executor is shutting down, so nothing will be written anyway.
                }
            }
        }
    }

    private void setRunner(Thread thread) {
        runnerLock.lock();
        try {
            runner = thread;
        } finally {
            runnerLock.unlock();
        }
    }
}
