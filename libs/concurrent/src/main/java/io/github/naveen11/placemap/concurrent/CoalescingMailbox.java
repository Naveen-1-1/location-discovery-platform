package io.github.naveen11.placemap.concurrent;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * A per-subscriber queue that keeps only the newest value for each key (ADR 0023). Live state is "latest value
 * wins" (bikes at a dock), so an older value still waiting for a slow reader is worthless once a newer one exists.
 *
 * <ul>
 *   <li><b>Never blocks the producer.</b> {@link #offer} takes the lock briefly and returns; a slow reader can only
 *       fall behind, never hold up the writer that serves everyone else.
 *   <li><b>Coalescing.</b> Offering a key that is already queued replaces its value in place: the key keeps its turn,
 *       and its newest value is what gets delivered. Values for one key are therefore never delivered out of order.
 *   <li><b>Bounded.</b> At most {@code capacity} keys wait. Offering a new key to a full mailbox clears it and queues
 *       one RESYNC instead, meaning "values were dropped; reload the full state". Nothing vanishes silently.
 *   <li><b>Close</b> drops everything and wakes the consumer; later offers are refused. Exactly one {@link #close}
 *       call returns {@code true}, so cleanup gated on it runs once however many paths close at the same time.
 * </ul>
 *
 * <p>RESYNC is delivered before any value queued after it. A consumer that reloads the full state on RESYNC must still
 * drop values that are not newer than what it reloaded: a value can be queued after the RESYNC yet be older than the
 * state read a moment later. live-hub compares snapshot versions for this.
 *
 * <p>One lock and one condition guard a {@link LinkedHashMap} in insertion order, which gives replace-in-place for
 * free. Checked against a plain LinkedHashMap model by Lincheck, and for ordering, capacity and close races by the
 * JCStress tests in {@code libs/concurrent-jcstress}.
 *
 * @param <K> key type, for example a dock id
 * @param <V> value type; {@code null} is rejected because the take methods use it for "nothing" or "closed"
 */
public final class CoalescingMailbox<K, V> {

    /** What {@link #offer} did with a value. */
    public enum Offer {
        /** The key was not queued; it now waits behind the others. */
        ADDED,
        /** The key was already queued; its value was replaced and it kept its turn. */
        REPLACED,
        /** The mailbox was full: everything queued was dropped and a RESYNC queued instead. */
        OVERFLOWED,
        /** The mailbox is closed; the value was dropped. */
        CLOSED
    }

    /** A queued value and the time its key first started waiting; replacing the value keeps the time. */
    private static final class Waiting<V> {
        V value;
        final long sinceNanos;

        Waiting(V value, long sinceNanos) {
            this.value = value;
            this.sinceNanos = sinceNanos;
        }
    }

    private final int capacity;
    private final V resyncMarker;
    private final LongSupplier nanoClock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    // All guarded by lock.
    private final LinkedHashMap<K, Waiting<V>> queued = new LinkedHashMap<>();
    private boolean resyncPending;
    private long resyncSinceNanos;
    private boolean closed;

    /**
     * @param capacity     most keys that may wait at once
     * @param resyncMarker the value the take methods return for RESYNC; compare it by identity
     */
    public CoalescingMailbox(int capacity, V resyncMarker) {
        this(capacity, resyncMarker, System::nanoTime);
    }

    /** As {@link #CoalescingMailbox(int, Object)}, with the clock behind {@link #oldestWaitNanos()} for tests. */
    public CoalescingMailbox(int capacity, V resyncMarker, LongSupplier nanoClock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1, was " + capacity);
        }
        this.capacity = capacity;
        this.resyncMarker = Objects.requireNonNull(resyncMarker, "resyncMarker");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /** Queues {@code value} for {@code key}, replacing a value already waiting for that key. Never blocks. */
    public Offer offer(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        lock.lock();
        try {
            if (closed) {
                return Offer.CLOSED;
            }
            Waiting<V> waiting = queued.get(key); // insertion order: get() does not move the key
            if (waiting != null) {
                waiting.value = value;
                return Offer.REPLACED;
            }
            if (queued.size() == capacity) {
                // The new value is dropped too: the consumer reloads the full state, which already holds it.
                markResync();
                return Offer.OVERFLOWED;
            }
            queued.put(key, new Waiting<>(value, nanoClock.getAsLong()));
            notEmpty.signal();
            return Offer.ADDED;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Drops everything queued and queues one RESYNC, as an overflow does. A new subscriber starts this way: its first
     * delivery is "load the full state".
     *
     * @return {@code false} if the mailbox is closed
     */
    public boolean requestResync() {
        lock.lock();
        try {
            if (closed) {
                return false;
            }
            markResync();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes the next delivery, waiting while there is none.
     *
     * @return the RESYNC marker, the oldest queued key's newest value, or {@code null} once the mailbox is closed
     * @throws InterruptedException if interrupted while waiting
     */
    public V take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (!closed) {
                V next = next();
                if (next != null) {
                    return next;
                }
                notEmpty.await();
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /** Removes the next delivery if there is one right now; {@code null} if empty or closed. */
    public V poll() {
        lock.lock();
        try {
            return closed ? null : next();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Moves up to {@code max} deliveries, in delivery order, into {@code sink} without waiting.
     *
     * @return how many were moved; 0 if empty or closed
     */
    public int drainTo(Collection<? super V> sink, int max) {
        Objects.requireNonNull(sink, "sink");
        lock.lock();
        try {
            int moved = 0;
            V next;
            while (!closed && moved < max && (next = next()) != null) {
                sink.add(next);
                moved++;
            }
            return moved;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Closes the mailbox: drops everything queued, wakes a waiting {@link #take}, and refuses later offers.
     *
     * @return {@code true} for the one call that closed it, {@code false} for every later call
     */
    public boolean close() {
        lock.lock();
        try {
            if (closed) {
                return false;
            }
            closed = true;
            queued.clear();
            resyncPending = false;
            notEmpty.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean isClosed() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    /** {@code true} if nothing waits: no RESYNC and no keys (always true once closed). */
    public boolean isEmpty() {
        lock.lock();
        try {
            return !resyncPending && queued.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    /** Keys waiting right now, not counting a pending RESYNC. Never more than {@link #capacity()}. */
    public int size() {
        lock.lock();
        try {
            return queued.size();
        } finally {
            lock.unlock();
        }
    }

    /** How long the oldest waiting delivery has waited, in nanoseconds; 0 if nothing waits. */
    public long oldestWaitNanos() {
        lock.lock();
        try {
            long since;
            if (resyncPending) {
                since = resyncSinceNanos; // RESYNC replaced everything older, so it is the oldest
            } else if (!queued.isEmpty()) {
                since = queued.firstEntry().getValue().sinceNanos;
            } else {
                return 0;
            }
            return Math.max(0, nanoClock.getAsLong() - since);
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return capacity;
    }

    private void markResync() {
        queued.clear(); // everything queued is older than the full state the consumer is about to reload
        if (!resyncPending) {
            resyncPending = true;
            resyncSinceNanos = nanoClock.getAsLong();
        }
        notEmpty.signal();
    }

    private V next() {
        if (resyncPending) {
            resyncPending = false;
            return resyncMarker;
        }
        Map.Entry<K, Waiting<V>> first = queued.pollFirstEntry();
        return first == null ? null : first.getValue().value;
    }
}
