package io.github.naveen11.placemap.livehub.sse;

import io.github.naveen11.placemap.concurrent.CoalescingMailbox;
import io.github.naveen11.placemap.concurrent.SerialDrain;
import io.github.naveen11.placemap.concurrent.SnapshotPublisher;
import io.github.naveen11.placemap.livehub.LiveMetrics;
import io.github.naveen11.placemap.livehub.state.Frame;
import io.github.naveen11.placemap.livehub.state.LiveKey;
import io.github.naveen11.placemap.livehub.state.LiveSnapshot;
import io.github.naveen11.placemap.livehub.state.Topic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * One SSE connection. The snapshot writer {@linkplain #deliver delivers} changes into the session's
 * {@link CoalescingMailbox} without ever blocking; the session's drain writes them to the client.
 *
 * <ul>
 *   <li><b>Connecting is a resync.</b> The mailbox starts with a RESYNC and the session subscribes before its drain
 *       starts. The drain takes the RESYNC, reads the current snapshot, sends it with {@code id: V}, and from then on
 *       skips frames with version ≤ V. An overflow takes the same path.
 *   <li><b>Two drain styles.</b> {@link ConnectionMode#VIRTUAL}: {@link #runLoop()} on its own virtual thread, waiting
 *       in {@code take()}. {@link ConnectionMode#POOL}: a {@link SerialDrain} job on a shared pool that writes what is
 *       waiting and returns.
 *   <li><b>Closing runs once.</b> Whoever closes first (the watchdog, a failed write, the container, shutdown) wins the
 *       mailbox's {@code close()}, and only that call runs the cleanup. The drain then ends the response: it is the
 *       only code that writes, so the response is never completed in the middle of a write.
 * </ul>
 */
public final class ClientSession implements SnapshotPublisher.Subscriber<LiveKey, Frame> {

    /** Why a session ended; the {@code reason} label of {@code placemap.live.sessions.closed}. */
    public enum End {
        CLIENT_GONE, SLOW, SHUTDOWN;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Frames written per batch before one flush. */
    static final int BATCH = 64;

    private static final long NOT_WRITING = Long.MIN_VALUE;

    private final String client;
    private final Set<Topic> topics;
    private final CoalescingMailbox<LiveKey, Frame> mailbox;
    private final EventSink sink;
    private final Supplier<LiveSnapshot> snapshots;
    private final byte[] retryLine;
    private final LongSupplier nanoClock;
    private final LiveMetrics metrics;
    private final Consumer<ClientSession> onClose;

    // Read by the watchdog.
    private volatile long writeStartedNanos = NOT_WRITING;
    private volatile long lastWriteNanos;
    private volatile End end;

    // How the drain runs: exactly one of these is used.
    private volatile Thread loopThread;   // VIRTUAL
    private volatile SerialDrain drain;   // POOL
    private volatile boolean completed;

    // Drain only (one drain at a time, so plain fields).
    private boolean started;
    private long loadedVersion = -1;
    private final ArrayList<Frame> batch = new ArrayList<>(BATCH);
    private final long[] written = new long[BATCH];

    ClientSession(String client, Set<Topic> topics, int mailboxCapacity, EventSink sink,
            Supplier<LiveSnapshot> snapshots, long retryMillis, LongSupplier nanoClock, LiveMetrics metrics,
            Consumer<ClientSession> onClose) {
        this.client = client;
        this.topics = EnumSet.copyOf(topics);
        this.mailbox = new CoalescingMailbox<>(mailboxCapacity, Frame.RESYNC, nanoClock);
        this.mailbox.requestResync(); // the first thing a new client gets is the whole snapshot
        this.sink = sink;
        this.snapshots = snapshots;
        this.retryLine = ("retry: " + retryMillis + "\n\n").getBytes(StandardCharsets.US_ASCII);
        this.nanoClock = nanoClock;
        this.metrics = metrics;
        this.onClose = onClose;
        this.lastWriteNanos = nanoClock.getAsLong();
    }

    String client() {
        return client;
    }

    Set<Topic> topics() {
        return topics;
    }

    End end() {
        return end;
    }

    /** Called on the snapshot writer thread for every change; keeps the ones this client asked for. Never blocks. */
    @Override
    public void deliver(LiveKey key, Frame frame) {
        if (key.topic() != null && topics.contains(key.topic())) {
            offer(key, frame);
        }
    }

    /** Pool mode: run drain jobs on {@code pool} from now on, starting with the first one. */
    void startOn(Executor pool) {
        drain = new SerialDrain(pool, this::drainOnce,
                () -> !completed && (!mailbox.isEmpty() || mailbox.isClosed()));
        wake();
    }

    /**
     * Virtual-thread mode: the whole life of the connection, on its own thread. Waits for frames, writes them in
     * batches, and ends the response once the session is closed.
     */
    void runLoop() {
        loopThread = Thread.currentThread();
        try {
            Frame first;
            while ((first = mailbox.take()) != null) {
                batch.add(first);
                mailbox.drainTo(batch, BATCH - 1);
                writeBatch();
            }
        } catch (InterruptedException | IOException | IllegalStateException e) {
            // Evicted or shut down (interrupt), or the client went away (write failed).
        } finally {
            close(End.CLIENT_GONE, false); // no-op if something else closed it first
            complete();
        }
    }

    /** Pool mode: one turn. Writes what is waiting without waiting for more, or ends the response once closed. */
    private void drainOnce() {
        if (completed) {
            return;
        }
        if (mailbox.isClosed()) {
            complete();
            return;
        }
        mailbox.drainTo(batch, BATCH);
        if (batch.isEmpty()) {
            return;
        }
        try {
            writeBatch();
        } catch (IOException | IllegalStateException e) {
            close(End.CLIENT_GONE, false);
            complete();
        }
    }

    /**
     * Closes the session. Only the first call does anything: it records why, runs the hub's cleanup, and wakes the
     * drain, interrupting a write that may be stuck on a client that stopped reading.
     */
    public void close(End reason) {
        close(reason, true);
    }

    /** {@code interruptDrain} is false when the drain itself closes: there is no write to break, it is the caller. */
    private void close(End reason, boolean interruptDrain) {
        if (!mailbox.close()) {
            return;
        }
        end = reason;
        onClose.accept(this);
        Thread loop = loopThread;
        if (loop != null && interruptDrain) {
            loop.interrupt(); // take() already woke on close; this breaks a blocked write
        }
        SerialDrain pooled = drain;
        if (pooled != null) {
            if (interruptDrain) {
                pooled.interruptRunning(); // only reaches this session's job, never the pool thread's next one
            }
            wake(); // one more turn, to end the response
        }
    }

    /**
     * Called by the watchdog about once a second: evicts the session if a write, or the oldest waiting frame, is
     * older than {@code evictAfterNanos}; otherwise queues a heartbeat if nothing was written for
     * {@code heartbeatNanos}.
     */
    void check(long evictAfterNanos, long heartbeatNanos) {
        long now = nanoClock.getAsLong();
        long writing = writeStartedNanos;
        if ((writing != NOT_WRITING && now - writing > evictAfterNanos) || mailbox.oldestWaitNanos() > evictAfterNanos) {
            close(End.SLOW);
        } else if (now - lastWriteNanos > heartbeatNanos) {
            offer(LiveKey.HEARTBEAT, Frame.HEARTBEAT);
        }
    }

    boolean isClosed() {
        return mailbox.isClosed();
    }

    private void offer(LiveKey key, Frame frame) {
        switch (mailbox.offer(key, frame)) {
            case REPLACED -> metrics.coalesced();
            case OVERFLOWED -> metrics.overflow();
            case ADDED, CLOSED -> {
            }
        }
        wake();
    }

    private void wake() {
        SerialDrain pooled = drain;
        if (pooled != null) {
            try {
                pooled.signal();
            } catch (RejectedExecutionException e) {
                // The pool is shutting down; shutdown closes every session anyway.
            }
        }
    }

    /** Writes the batch, then flushes once. Drain only. */
    private void writeBatch() throws IOException {
        writeStartedNanos = nanoClock.getAsLong();
        int delivered = 0;
        try {
            if (!started) {
                sink.write(retryLine); // reconnect after a random 2-7 s, so a restart does not cause a stampede
                started = true;
            }
            for (Frame frame : batch) {
                if (frame == Frame.RESYNC) {
                    writeSnapshot();
                } else if (frame == Frame.HEARTBEAT) {
                    sink.write(frame.bytes());
                } else if (frame.version() > loadedVersion) {
                    sink.write(frame.bytes());
                    written[delivered++] = frame.publishedNanos();
                }
            }
            sink.flush();
        } finally {
            batch.clear();
            long done = nanoClock.getAsLong();
            lastWriteNanos = done;
            writeStartedNanos = NOT_WRITING;
            for (int i = 0; i < delivered; i++) {
                metrics.fanoutLag(done - written[i]);
            }
        }
    }

    private void writeSnapshot() throws IOException {
        LiveSnapshot snapshot = snapshots.get(); // after subscribing (or after the overflow), never before
        loadedVersion = snapshot.version();
        byte[] id = ("id: " + snapshot.version() + "\n").getBytes(StandardCharsets.US_ASCII);
        for (Topic topic : topics) {
            sink.write(id);
            sink.write(snapshot.topic(topic).snapshotEvent());
        }
        metrics.snapshotSent();
    }

    /** Ends the response once. Drain only, so it never races a write. */
    private void complete() {
        if (!completed) {
            completed = true;
            sink.complete();
        }
    }
}
