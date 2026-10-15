package io.github.naveen11.placemap.livehub.sse;

import io.github.naveen11.placemap.livehub.state.Topic;
import jakarta.servlet.http.HttpServletRequest;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /live?topics=bikes,...}: one SSE stream per browser tab. Spring MVC holds the request open with an
 * {@link SseEmitter} and the request thread returns at once; the session's drain writes to it from its own thread.
 */
@RestController
public class LiveController {

    /** An open stream never times out on its own: the watchdog and heartbeats decide when it ends. */
    private static final long NO_TIMEOUT = 0L;

    private final LiveHub hub;
    private final ClientIds clientIds = new ClientIds();

    public LiveController(LiveHub hub) {
        this.hub = hub;
    }

    @GetMapping(path = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestParam(required = false) String topics, HttpServletRequest request) {
        Set<Topic> wanted = topics(topics).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "topics must be a comma-separated list of " + streamed().stream().map(Topic::wire).toList()));
        String client = clientIds.of(request.getRemoteAddr()); // the real client behind a trusted proxy
        var emitter = new SseEmitter(NO_TIMEOUT);
        ClientSession session;
        try {
            session = hub.open(client, wanted, new EmitterSink(emitter));
        } catch (LiveHub.RefusedException e) {
            return ResponseEntity.status(e.refusal() == LiveHub.Refusal.CLIENT_FULL
                            ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, "10")
                    .build();
        }
        // The client went away or the container ended the request: the session's one-time cleanup runs.
        emitter.onCompletion(() -> session.close(ClientSession.End.CLIENT_GONE));
        emitter.onTimeout(() -> session.close(ClientSession.End.CLIENT_GONE));
        emitter.onError(error -> session.close(ClientSession.End.CLIENT_GONE));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .header("X-Accel-Buffering", "no") // ask proxies not to buffer the stream
                .body(emitter);
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
