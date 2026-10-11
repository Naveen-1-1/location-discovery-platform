package io.github.naveen11.placemap.livehub.sse;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletOutputStream;
import java.io.IOException;

/**
 * An SSE response held open with {@link AsyncContext}. Writes are blocking servlet I/O from the session's drain
 * thread; a client that stops reading eventually blocks them until the watchdog evicts it, or Tomcat's write timeout
 * fires as a backstop (ADR 0023).
 */
final class ServletEventSink implements EventSink {

    private final AsyncContext context;
    private final ServletOutputStream out;

    ServletEventSink(AsyncContext context) throws IOException {
        this.context = context;
        this.out = context.getResponse().getOutputStream();
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        out.write(bytes);
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void complete() {
        try {
            context.complete();
        } catch (IllegalStateException e) {
            // The container already ended the request (the client went away).
        }
    }
}
