package io.github.naveen11.placemap.api.routing;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.timelimiter.TimeLimiter;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Every call to the routing engine goes through here: Resilience4j's shared bulkhead (which never waits), its circuit
 * breaker, and a time limiter that stops waiting whatever the adapter's own HTTP timeout. A late answer is abandoned,
 * so the caller's fallback or error goes out on time. All three are configured under {@code resilience4j.*}.
 */
public class GuardedRouting {

    /** A call that gave no answer. {@link #reason()} is what clients see: {@code timeout}, {@code breaker_open}, … */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String reason, Throwable cause) {
            super(reason, cause, false, false);
        }

        public String reason() {
            return getMessage();
        }

        /** The engine answered that there is no route: not a failure of the engine. */
        public boolean unroutable() {
            return "unroutable".equals(getMessage());
        }
    }

    private final RoutingClient client;
    private final Bulkhead bulkhead;
    private final CircuitBreaker breaker;
    private final Executor calls;

    /** @param calls where each engine call runs, so the time limiter can stop waiting for it */
    public GuardedRouting(RoutingClient client, Bulkhead bulkhead, CircuitBreaker breaker, Executor calls) {
        this.client = client;
        this.bulkhead = bulkhead;
        this.breaker = breaker;
        this.calls = calls;
    }

    /** The engine's name, reported as {@code travelTimeSource}. */
    public String engine() {
        return client.name();
    }

    /** @throws Refused when the bulkhead is full, the breaker is open, the call fails or outlasts {@code limit} */
    public <T> T call(TimeLimiter limit, Function<RoutingClient, T> call) {
        Callable<T> timed = limit.decorateFutureSupplier(
                () -> CompletableFuture.supplyAsync(() -> call.apply(client), calls));
        try {
            // The bulkhead outermost: a full one refuses before the breaker counts anything.
            return Bulkhead.decorateCallable(bulkhead, CircuitBreaker.decorateCallable(breaker, timed)).call();
        } catch (Exception e) {
            throw new Refused(reason(e), e);
        }
    }

    static String reason(Throwable cause) {
        return switch (cause) {
            case BulkheadFullException _ -> "bulkhead_full";
            case CallNotPermittedException _ -> "breaker_open";
            case TimeoutException _ -> "timeout";
            case RoutingException failure -> failure.kind().name().toLowerCase(Locale.ROOT);
            default -> "error";
        };
    }
}
