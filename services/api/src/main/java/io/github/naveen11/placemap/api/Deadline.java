package io.github.naveen11.placemap.api;

import java.time.Duration;

/**
 * The moment a request must be answered by (ADR 0026), passed explicitly from the controller down to every wait, so
 * nothing waits longer than the request has left.
 */
public record Deadline(long nanos) {

    public static Deadline after(Duration timeout) {
        return new Deadline(System.nanoTime() + timeout.toNanos());
    }

    /** Time left, never negative. */
    public Duration remaining() {
        return Duration.ofNanos(Math.max(0, nanos - System.nanoTime()));
    }
}
