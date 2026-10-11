package io.github.naveen11.placemap.api.routing;

/**
 * A routing call that gave no answer. Callers fall back (ADR 0026): nearby sorts by straight-line distance, while
 * routes and isochrones report the failure.
 */
public class RoutingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** No answer within the call's timeout. */
        TIMEOUT,
        /** The engine refused because of a quota or rate limit. */
        RATE_LIMITED,
        /** The engine is down, unreachable, not configured, or answered with a server error. */
        UNAVAILABLE,
        /** No route exists, for example from a point far from any path. */
        UNROUTABLE,
        /** The engine rejected the request itself: a bad parameter, a limit, or a wrong API key. */
        REJECTED
    }

    private final Kind kind;

    public RoutingException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public RoutingException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
