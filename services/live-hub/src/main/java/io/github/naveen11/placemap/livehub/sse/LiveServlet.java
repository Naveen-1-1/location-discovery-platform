package io.github.naveen11.placemap.livehub.sse;

import io.github.naveen11.placemap.livehub.state.Topic;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serial;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * {@code GET /live?topics=bikes,...}: one SSE stream per browser tab (ADR 0018). Holds the request open with
 * {@link AsyncContext} and hands it to {@link LiveHub}; the request thread returns at once.
 *
 * <p>A plain servlet rather than a Spring MVC endpoint: the stream needs nothing from MVC, and its writes then go
 * straight to the response, from the session's own drain thread.
 */
public final class LiveServlet extends HttpServlet {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient LiveHub hub;
    private final transient ClientIds clientIds = new ClientIds();

    public LiveServlet(LiveHub hub) {
        this.hub = hub;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Optional<Set<Topic>> topics = topics(request.getParameter("topics"));
        if (topics.isEmpty()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "topics must be a comma-separated list of "
                    + streamed().stream().map(Topic::wire).toList());
            return;
        }
        String client = clientIds.of(request.getRemoteAddr()); // the real client behind a trusted proxy (ADR 0028)

        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no"); // ask proxies not to buffer the stream
        AsyncContext context = request.startAsync();
        context.setTimeout(0); // the watchdog and heartbeats decide when a stream ends, not the container
        ClientSession session;
        try {
            session = hub.open(client, topics.get(), new ServletEventSink(context));
        } catch (LiveHub.RefusedException e) {
            // Nothing is written yet, so the status can still change.
            response.reset();
            response.setStatus(e.refusal() == LiveHub.Refusal.CLIENT_FULL ? 429 : HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Retry-After", "10");
            context.complete();
            return;
        }
        context.addListener(new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
                session.close(ClientSession.End.CLIENT_GONE);
            }

            @Override
            public void onTimeout(AsyncEvent event) {
                session.close(ClientSession.End.CLIENT_GONE);
            }

            @Override
            public void onError(AsyncEvent event) {
                // The client went away. End the response here, or Tomcat would dispatch its error page into a
                // stream that is already broken; a write still under way fails, and the drain stops on that.
                session.close(ClientSession.End.CLIENT_GONE);
                try {
                    event.getAsyncContext().complete();
                } catch (IllegalStateException e) {
                    // already completed
                }
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
            }
        });
    }

    /** The topics asked for, every streamed one if none; empty if any name is unknown or not streamed. */
    static Optional<Set<Topic>> topics(String parameter) {
        if (parameter == null || parameter.isBlank()) {
            return Optional.of(streamed());
        }
        var topics = EnumSet.noneOf(Topic.class);
        for (String name : parameter.split(",")) {
            Optional<Topic> topic = Topic.fromWire(name.strip()).filter(Topic::streamed);
            if (topic.isEmpty()) {
                return Optional.empty();
            }
            topics.add(topic.get());
        }
        return Optional.of(topics);
    }

    private static EnumSet<Topic> streamed() {
        var topics = EnumSet.noneOf(Topic.class);
        for (Topic topic : Topic.values()) {
            if (topic.streamed()) {
                topics.add(topic);
            }
        }
        return topics;
    }
}
