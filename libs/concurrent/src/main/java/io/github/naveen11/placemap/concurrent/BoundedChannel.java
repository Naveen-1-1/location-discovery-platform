package io.github.naveen11.placemap.concurrent;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A fixed-capacity FIFO queue between two pipeline stages that can also be closed or failed.
 *
 * <ul>
 *   <li><b>Backpressure:</b> {@link #put} blocks while the channel is full, so a fast producer slows to the pace of
 *       its consumers.
 *   <li><b>Close</b> means "no more items are coming". Items already accepted are still delivered; once they are
 *       drained, {@link #take} returns {@code null} (end of stream). Puts after close throw
 *       {@link ChannelClosedException}.
 *   <li><b>Fail</b> means "stop now". Buffered items are dropped, and every blocked or later call throws
 *       {@link ChannelFailedException} carrying the first failure's cause. Nothing is lost for good: the import only
 *       marks a batch done after it is committed, so dropped batches are redone on resume.
 * </ul>
 *
 * <p>Built like {@link java.util.concurrent.ArrayBlockingQueue} on the inside (a ring buffer, one lock, two
 * conditions), but the closed and failed flags live under the same lock as the items. That is what makes close
 * race-free: a put either lands before close, and is then delivered before end of stream, or it sees the flag and
 * throws. A flag bolted onto an ArrayBlockingQueue leaves a window where a put passes the check, close and the final
 * drain happen, and the item lands after the consumer has already seen end of stream. The JCStress tests in
 * {@code libs/concurrent-jcstress} check exactly that window.
 *
 * @param <T> item type; {@code null} items are rejected because {@code null} signals end of stream
 */
public final class BoundedChannel<T> {

    private final Object[] items;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    // All guarded by lock.
    private int head;          // index of the oldest item
    private int count;
    private boolean closed;
    private Throwable failure; // non-null once failed; the first cause wins

    public BoundedChannel(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1, was " + capacity);
        }
        this.items = new Object[capacity];
    }

    /**
     * Adds an item, waiting while the channel is full.
     *
     * @throws ChannelClosedException if the channel is closed, before or while waiting; the item was not accepted
     * @throws ChannelFailedException if the channel has failed, before or while waiting
     * @throws InterruptedException if interrupted while waiting; the item was not accepted
     */
    public void put(T item) throws InterruptedException {
        Objects.requireNonNull(item, "item");
        lock.lockInterruptibly();
        try {
            while (true) {
                checkAcceptingPuts();
                if (count < items.length) {
                    enqueue(item);
                    return;
                }
                notFull.await();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Adds an item if there is room right now.
     *
     * @return {@code false} if the channel is full
     * @throws ChannelClosedException if the channel is closed
     * @throws ChannelFailedException if the channel has failed
     */
    public boolean offer(T item) {
        Objects.requireNonNull(item, "item");
        lock.lock();
        try {
            checkAcceptingPuts();
            if (count == items.length) {
                return false;
            }
            enqueue(item);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes the oldest item, waiting while the channel is empty and still open.
     *
     * @return the item, or {@code null} once the channel is closed and every accepted item has been taken
     * @throws ChannelFailedException if the channel has failed, before or while waiting
     * @throws InterruptedException if interrupted while waiting
     */
    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                if (failure != null) {
                    throw new ChannelFailedException(failure);
                }
                if (count > 0) {
                    return dequeue();
                }
                if (closed) {
                    return null;
                }
                notEmpty.await();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes the oldest item if there is one right now.
     *
     * @return the item, or {@code null} if the channel is empty (open or closed)
     * @throws ChannelFailedException if the channel has failed
     */
    public T poll() {
        lock.lock();
        try {
            if (failure != null) {
                throw new ChannelFailedException(failure);
            }
            return count > 0 ? dequeue() : null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Signals that no more items are coming. Consumers drain what is buffered, then see end of stream; blocked and
     * later producers get {@link ChannelClosedException}. Idempotent, and a no-op on a failed channel.
     */
    public void close() {
        lock.lock();
        try {
            if (closed || failure != null) {
                return;
            }
            closed = true;
            notEmpty.signalAll(); // waiting consumers: the buffer is empty, so this is end of stream
            notFull.signalAll();  // waiting producers: their items will not be accepted
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stops the channel at once: buffered items are dropped, and every blocked or later call throws
     * {@link ChannelFailedException} with this cause. Only the first failure is kept; later calls are no-ops.
     * Failing a closed channel is allowed (a consumer may fail after the producer finished).
     */
    public void fail(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        lock.lock();
        try {
            if (failure != null) {
                return;
            }
            failure = cause;
            Arrays.fill(items, null); // let dropped batches be garbage-collected now
            head = 0;
            count = 0;
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Items buffered right now (for queue-fill metrics). */
    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return items.length;
    }

    private void checkAcceptingPuts() {
        // Failure first: once failed, the cause matters more than the fact that the channel was also closed.
        if (failure != null) {
            throw new ChannelFailedException(failure);
        }
        if (closed) {
            throw new ChannelClosedException();
        }
    }

    private void enqueue(T item) {
        items[(head + count) % items.length] = item;
        count++;
        notEmpty.signal(); // one new item can satisfy one waiting consumer
    }

    @SuppressWarnings("unchecked")
    private T dequeue() {
        T item = (T) items[head];
        items[head] = null;
        head = (head + 1) % items.length;
        count--;
        notFull.signal(); // one free slot can satisfy one waiting producer
        return item;
    }
}
