package io.github.naveen11.placemap.livehub.sse;

import java.io.IOException;

/** Where a session's events go: the servlet response in production, a recorder in tests. One writer at a time. */
public interface EventSink {

    /** May block while the client is slow to read. */
    void write(byte[] bytes) throws IOException;

    void flush() throws IOException;

    /** Ends the response. Called once, by the session's drain, after its last write. */
    void complete();
}
