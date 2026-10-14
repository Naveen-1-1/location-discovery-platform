package io.github.naveen11.placemap.api.routing;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the routing engine: our own Valhalla when {@code placemap.routing.engine=valhalla}; otherwise openrouteservice
 * when ORS_API_KEY is set, or a client that always falls back.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RoutingProperties.class)
class RoutingConfig {

    private static final Logger log = LoggerFactory.getLogger(RoutingConfig.class);

    /** The engine behind the shared bulkhead and circuit breaker, both exported as metrics. */
    @Bean
    GuardedRouting guardedRouting(RoutingClient client, RoutingProperties properties, MeterRegistry registry) {
        var bulkheads = BulkheadRegistry.ofDefaults();
        var bulkhead = bulkheads.bulkhead("routing", BulkheadConfig.custom()
                .maxConcurrentCalls(properties.concurrency())
                .maxWaitDuration(Duration.ZERO)   // full means refused at once; waiting would only miss deadlines
                .build());
        var breakers = CircuitBreakerRegistry.ofDefaults();
        var breaker = breakers.circuitBreaker("routing", GuardedRouting.breakerConfig());
        TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheads).bindTo(registry);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(registry);
        return new GuardedRouting(client, bulkhead, breaker);
    }

    @Bean
    RoutingClient routingClient(RoutingProperties properties, RestClient.Builder builder) {
        if (properties.engine() == RoutingProperties.Engine.VALHALLA) {
            RoutingProperties.Valhalla valhalla = properties.valhalla();
            log.info("Routing with Valhalla at {}", valhalla.baseUrl());
            RestClient.Builder base = ValhallaRoutingClient.configure(builder, valhalla);
            return new ValhallaRoutingClient(base.clone().requestFactory(jdk(valhalla.matrixTimeout())).build(),
                    base.clone().requestFactory(jdk(valhalla.timeout())).build(), valhalla.maxSnapMeters());
        }
        RoutingProperties.Ors ors = properties.ors();
        if (ors.apiKey() == null || ors.apiKey().isBlank()) {
            log.warn("ORS_API_KEY is not set: travel times fall back to straight-line distance");
            return RoutingClient.unavailable("no routing engine configured (ORS_API_KEY is empty)");
        }
        RestClient.Builder base = OrsRoutingClient.configure(builder, ors);
        return new OrsRoutingClient(base.clone().requestFactory(jdk(ors.matrixTimeout())).build(),
                base.clone().requestFactory(jdk(ors.timeout())).build(), ors.maxSnapMeters());
    }

    /** The JDK client, as the rest of the app uses: blocking calls are cheap on virtual threads. */
    static ClientHttpRequestFactory jdk(Duration timeout) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        return factory;
    }
}
