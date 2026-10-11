package io.github.naveen11.placemap.concurrent;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToLongFunction;

/**
 * Hands immutable, versioned snapshots and their per-key changes from one writer thread to many subscribers
 * (ADR 0023).
 *
 * <ul>
 *   <li><b>Single writer.</b> Only one thread may {@link #publish}; the first caller becomes the writer and any other
 *       thread is refused. All mutable live state stays on that thread, so building a snapshot needs no locks.
 *   <li><b>Never torn, never backwards.</b> A snapshot becomes visible in one {@link AtomicReference#set}, and each
 *       version must be higher than the last. Readers see a whole snapshot, and never an older one after a newer.
 *   <li><b>Subscribe, then read.</b> {@link #publish} makes the snapshot visible first and only then delivers the
 *       changes. A subscriber that {@linkplain #subscribe registers} first and reads {@link #current()} second
 *       therefore either gets each change delivered, or reads a snapshot that already contains it. Reading first
 *       would leave a window in which a change is neither (the JCStress tests show both orders).
 * </ul>
 *
 * @param <S> snapshot type; must be immutable once published
 * @param <K> change key type
 * @param <V> change value type
 */
public final class SnapshotPublisher<S, K, V> {

    /** Receives changes on the writer thread, so it must not block (a {@link CoalescingMailbox} offer is fine). */
    @FunctionalInterface
    public interface Subscriber<K, V> {
        void deliver(K key, V value);
    }

    private final ToLongFunction<? super S> versionOf;
    private final AtomicReference<S> current;
    private final AtomicReference<Thread> writer = new AtomicReference<>();
    private final Set<Subscriber<K, V>> subscribers = ConcurrentHashMap.newKeySet();

    /**
     * @param initial   the snapshot readers see before the first publish
     * @param versionOf a snapshot's version; each published snapshot must have a higher one
     */
    public SnapshotPublisher(S initial, ToLongFunction<? super S> versionOf) {
        this.current = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
        this.versionOf = Objects.requireNonNull(versionOf, "versionOf");
    }

    /** The newest published snapshot. */
    public S current() {
        return current.get();
    }

    /** Starts delivering changes to {@code subscriber}. Read {@link #current()} after this call, never before. */
    public void subscribe(Subscriber<K, V> subscriber) {
        subscribers.add(Objects.requireNonNull(subscriber, "subscriber"));
    }

    public void unsubscribe(Subscriber<K, V> subscriber) {
        subscribers.remove(subscriber);
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    /**
     * Makes {@code next} the current snapshot, then delivers each change to every subscriber.
     *
     * @param changes what changed since the previous snapshot, in delivery order; already contained in {@code next}
     * @throws IllegalStateException    if called from a thread other than the first one that published
     * @throws IllegalArgumentException if {@code next} is not newer than the current snapshot
     */
    public void publish(S next, Map<? extends K, ? extends V> changes) {
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(changes, "changes");
        Thread me = Thread.currentThread();
        if (!writer.compareAndSet(null, me) && writer.get() != me) {
            throw new IllegalStateException("only " + writer.get().getName() + " may publish, not " + me.getName());
        }
        long from = versionOf.applyAsLong(current.get());
        long to = versionOf.applyAsLong(next);
        if (to <= from) {
            throw new IllegalArgumentException("version " + to + " is not newer than " + from);
        }
        current.set(next);
        if (changes.isEmpty()) {
            return;
        }
        for (Subscriber<K, V> subscriber : subscribers) {
            changes.forEach(subscriber::deliver);
        }
    }
}
