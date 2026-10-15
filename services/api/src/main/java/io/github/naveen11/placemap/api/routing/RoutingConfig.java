package io.github.naveen11.placemap.api.routing;

import com.uber.h3core.H3Core;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
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

    /** The engine behind the shared {@code routing} bulkhead and circuit breaker (resilience4j.* settings). */
    @Bean
    GuardedRouting guardedRouting(RoutingClient client, BulkheadRegistry bulkheads, CircuitBreakerRegistry breakers,
            @Qualifier("routingCalls") Executor calls) {
        return new GuardedRouting(client, bulkheads.bulkhead("routing"), breakers.circuitBreaker("routing"), calls);
    }

    /** Engine calls, one virtual thread each, so a time limiter can stop waiting for one. */
    @Bean(defaultCandidate = false)
    SimpleAsyncTaskExecutor routingCalls(SimpleAsyncTaskExecutorBuilder builder) {
        return builder.threadNamePrefix("routing-call-").taskTerminationTimeout(Duration.ofSeconds(5)).build();
    }

    @Bean
    RoutingClient routingClient(RoutingProperties properties, RestClient.Builder builder, H3Core h3) {
        if (properties.engine() == RoutingProperties.Engine.VALHALLA) {
            RoutingProperties.Valhalla valhalla = properties.valhalla();
            log.info("Routing with Valhalla at {}", valhalla.baseUrl());
            RestClient.Builder base = ValhallaRoutingClient.configure(builder, valhalla);
            return new ValhallaRoutingClient(base.clone().requestFactory(jdk(valhalla.matrixTimeout())).build(),
                    base.clone().requestFactory(jdk(valhalla.timeout())).build(), valhalla.maxSnapMeters(), h3);
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
