package io.github.naveen11.placemap.concurrent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks the highest batch number N such that every batch 0..N is done, while batches finish out of order on many
 * threads (ADR 0020, 0036). Lock-free: threads publish finished batch numbers in a concurrent set and move the
 * watermark forward with compare-and-set, helping each other along.
 *
 * <p>Guarantees, checked by Lincheck and JCStress:
 * <ul>
 *   <li>The watermark never passes a batch that is not done.
 *   <li>Once every {@code markDone} call has returned, the watermark covers the whole contiguous prefix: no advance
 *       is lost when two threads race.
 *   <li>Readers never see an intermediate value. With batches 1 and 2 already done, {@code markDone(0)} moves the
 *       watermark from -1 straight to 2, never through 0 or 1.
 * </ul>
 */
public final class ContiguousWatermark {

    private final AtomicLong watermark = new AtomicLong(-1);
    // Done batch numbers above the watermark. Its size is bounded by the batches in flight, because entries are
    // removed as soon as the watermark covers them.
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();

    /** The highest N with batches 0..N all done, or -1 if batch 0 is not done yet. */
    public long current() {
        return watermark.get();
    }

    /**
     * Records batch {@code seq} as done and moves the watermark forward if it closes a gap. Marking a batch twice is
     * harmless (a resumed run replays the batches it finds already committed).
     *
     * <p>Returns nothing on purpose: when another thread helps, the watermark this call could report is not
     * well-defined. Read {@link #current()} instead.
     */
    public void markDone(long seq) {
        if (seq < 0) {
            throw new IllegalArgumentException("batch numbers start at 0, was " + seq);
        }
        if (seq <= watermark.get()) {
            return; // already covered, so this is a repeat
        }
        pending.add(seq);
        advance();
        // A repeat call can race past the check above while another thread covers seq and removes it, then add it
        // back. Clear such a stale entry; anything at or below the watermark is never looked at again.
        if (seq <= watermark.get()) {
            pending.remove(seq);
        }
    }

    private void advance() {
        while (true) {
            long from = watermark.get();
            long to = from;
            while (pending.contains(to + 1)) {
                to++;
            }
            if (to == from) {
                // Nothing to add. Return only if nobody moved the watermark while we looked: then, at the moment of
                // the failed lookup, batch from+1 was truly not done. If it moved, the entry we looked for may just
                // have been covered and removed, and our own batch may now be next in line. Returning then would let
                // a reader see a watermark below a batch whose markDone had already returned, so look again.
                if (watermark.get() == from) {
                    return;
                }
                continue;
            }
            // One jump over the whole run we found, so readers never see a value part-way through it.
            if (watermark.compareAndSet(from, to)) {
                for (long s = from + 1; s <= to; s++) {
                    pending.remove(s);
                }
            }
            // Success or not, look again: batches may have been marked since the scan.
        }
    }

    /** For tests: entries waiting above the watermark. */
    int pendingSize() {
        return pending.size();
    }
}
