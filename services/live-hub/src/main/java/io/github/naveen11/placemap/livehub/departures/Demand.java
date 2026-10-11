package io.github.naveen11.placemap.livehub.departures;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Remembers when departures were last asked for, so their 2 MB feed is read only while someone is looking. */
public final class Demand {

    private final long windowMillis;
    private final LongSupplier millisClock;
    private final AtomicLong last = new AtomicLong(Long.MIN_VALUE / 2);

    public Demand(Duration window, LongSupplier millisClock) {
        this.windowMillis = window.toMillis();
        this.millisClock = millisClock;
    }

    /** Records a request. @return {@code true} if nobody had asked within the window, so the data may be old */
    public boolean mark() {
        long now = millisClock.getAsLong();
        return now - last.getAndSet(now) > windowMillis;
    }

    public boolean recent() {
        return millisClock.getAsLong() - last.get() <= windowMillis;
    }
}
