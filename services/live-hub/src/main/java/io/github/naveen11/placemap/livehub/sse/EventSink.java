package io.github.naveen11.placemap.livehub.sse;

import java.io.IOException;

/** Where a session's events go: its SSE emitter in production, a recorder in tests. One writer at a time. */
public interface EventSink {

    /** Writes now, or holds the bytes until {@link #flush()}; either may block while the client is slow to read. */
    void write(byte[] bytes) throws IOException;

    void flush() throws IOException;

    /** Ends the response. Called once, by the session's drain, after its last write. */
    void complete();
}
