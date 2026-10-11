package io.github.naveen11.placemap.concurrent;

import java.io.Serial;

/**
 * Thrown by every operation on a {@link BoundedChannel} after it has failed. The cause is the first failure passed to
 * {@link BoundedChannel#fail}; each throw is a new exception so every thread gets its own stack trace.
 */
public final class ChannelFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ChannelFailedException(Throwable cause) {
        super("channel failed: " + cause, cause);
    }
}
