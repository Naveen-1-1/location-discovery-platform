package io.github.naveen11.placemap.concurrent;

import java.io.Serial;

/** Thrown when an item is put into a {@link BoundedChannel} that has been closed. The item was not accepted. */
public final class ChannelClosedException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ChannelClosedException() {
        super("channel is closed");
    }
}
