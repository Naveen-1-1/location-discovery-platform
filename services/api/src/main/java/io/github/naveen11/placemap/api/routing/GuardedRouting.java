package io.github.naveen11.placemap.api.routing;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Every call to the routing engine goes through here: one shared bulkhead that never waits, one circuit
 * breaker, and a time limit enforced on this side whatever the adapter's own HTTP timeout. A late answer is
 * abandoned, so the caller's fallback or error goes out on time.
 */
public class GuardedRouting implements AutoCloseable {

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
    private final ExecutorService calls =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("routing-call-", 1).factory());

    public GuardedRouting(RoutingClient client, Bulkhead bulkhead, CircuitBreaker breaker) {
        this.client = client;
        this.bulkhead = bulkhead;
        this.breaker = breaker;
    }

    /**
     * Opens when half of at least 5 calls in the last 10 s failed (timeouts, quota, outages), then lets 2 trial calls
     * through after 10 s. "No route from here" is an answer, not a failure, so it never counts.
     */
    public static CircuitBreakerConfig breakerConfig() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.TIME_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(2)
                .ignoreException(e -> e instanceof RoutingException r && r.kind() == RoutingException.Kind.UNROUTABLE)
                .build();
    }

    /** The engine's name, reported as {@code travelTimeSource}. */
    public String engine() {
        return client.name();
    }

    /** @throws Refused when the bulkhead is full, the breaker is open, the call fails or takes longer than timeout */
    public <T> T call(Duration timeout, Function<RoutingClient, T> call) {
        if (!bulkhead.tryAcquirePermission()) {
            throw new Refused("bulkhead_full", null);
        }
        try {
            if (!breaker.tryAcquirePermission()) {
                throw new Refused("breaker_open", null);
            }
            long start = System.nanoTime();
            try {
                T result = CompletableFuture.supplyAsync(() -> call.apply(client), calls)
                        .orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS)
                        .join();
                breaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                return result;
            } catch (CompletionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, cause);
                throw new Refused(reason(cause), cause);
            }
        } finally {
            bulkhead.onComplete();
        }
    }

    static String reason(Throwable cause) {
        if (cause instanceof TimeoutException) {
            return "timeout";
        }
        if (cause instanceof RoutingException failure) {
            return failure.kind().name().toLowerCase(Locale.ROOT);
        }
        return "error";
    }

    @Override
    public void close() {
        calls.close();
    }
}
