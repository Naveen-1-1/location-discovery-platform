package io.github.naveen11.placemap.livehub.sse;

/** How connections get threads (ADR 0041). Switchable so the two can be benchmarked against each other (L8). */
public enum ConnectionMode {
    /** One virtual thread per connection, running a plain wait-then-write loop. */
    VIRTUAL,
    /** A fixed pool of platform threads that take turns: a connection with news queues a short drain job. */
    POOL
}
