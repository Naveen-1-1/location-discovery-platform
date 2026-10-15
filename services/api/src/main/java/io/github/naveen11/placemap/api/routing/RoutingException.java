package io.github.naveen11.placemap.api.routing;

import java.util.function.Predicate;

/**
 * A routing call that gave no answer. Callers fall back: nearby sorts by straight-line distance, while
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

    /** For the circuit breaker (named in its settings): "no route from here" is an answer, not a failure. */
    public static final class Unroutable implements Predicate<Throwable> {

        @Override
        public boolean test(Throwable e) {
            return e instanceof RoutingException failure && failure.kind() == Kind.UNROUTABLE;
        }
    }
}
