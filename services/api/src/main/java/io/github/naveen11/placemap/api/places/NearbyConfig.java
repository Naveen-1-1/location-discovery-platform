package io.github.naveen11.placemap.api.places;

import com.github.benmanes.caffeine.cache.Ticker;
import io.github.naveen11.placemap.api.routing.RoutingClient;
import io.github.naveen11.placemap.api.routing.RoutingException;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires nearby search with its bulkheads and the routing circuit breaker (ADR 0026), all exported as metrics. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NearbyProperties.class)
class NearbyConfig {

    @Bean
    NearbyService nearbyService(PlaceRepository places, RoutingClient routing, NearbyProperties properties,
            MeterRegistry registry) {
        var bulkheads = BulkheadRegistry.ofDefaults();
        var database = bulkheads.bulkhead("database", noWaiting(properties.databaseConcurrency()));
        var routingBulkhead = bulkheads.bulkhead("routing", noWaiting(properties.routingConcurrency()));
        var breakers = CircuitBreakerRegistry.ofDefaults();
        var breaker = breakers.circuitBreaker("routing", routingBreaker());
        TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheads).bindTo(registry);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(registry);
        return new NearbyService(places, routing, properties, database, routingBulkhead, breaker, registry,
                Ticker.systemTicker());
    }

    /** Full means rejected at once: waiting would only push the request past its deadline. */
    private static BulkheadConfig noWaiting(int permits) {
        return BulkheadConfig.custom().maxConcurrentCalls(permits).maxWaitDuration(Duration.ZERO).build();
    }

    /**
     * Opens when half of at least 5 calls in the last 10 s failed (timeouts, quota, outages), then lets 2 trial calls
     * through after 10 s. "No route from here" is an answer, not a failure, so it never counts.
     */
    static CircuitBreakerConfig routingBreaker() {
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
}
