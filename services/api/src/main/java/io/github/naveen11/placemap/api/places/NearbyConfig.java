package io.github.naveen11.placemap.api.places;

import com.github.benmanes.caffeine.cache.Ticker;
import com.uber.h3core.H3Core;
import io.github.naveen11.placemap.api.routing.GuardedRouting;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/** Wires nearby search: the database bulkhead and the travel-time limit (resilience4j.* settings), and its loaders. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NearbyProperties.class)
class NearbyConfig {

    /** The time "open now" is judged at; tests replace it. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** H3's native library, loaded once for the origin snapper and the routing engine's distance checks. */
    @Bean
    H3Core h3Core() throws IOException {
        return H3Core.newInstance();
    }

    /** One bulkhead for every database search, nearby and name search alike. */
    @Bean
    Bulkhead databaseBulkhead(BulkheadRegistry bulkheads) {
        return bulkheads.bulkhead("database");
    }

    /** Shared nearby loads, one virtual thread each. */
    @Bean(defaultCandidate = false)
    SimpleAsyncTaskExecutor nearbyLoaders(SimpleAsyncTaskExecutorBuilder builder) {
        return builder.threadNamePrefix("nearby-load-").taskTerminationTimeout(Duration.ofSeconds(5)).build();
    }

    @Bean
    NearbyService nearbyService(PlaceRepository places, GuardedRouting routing, TimeLimiterRegistry limits,
            NearbyProperties properties, Bulkhead databaseBulkhead, MeterRegistry registry,
            @Qualifier("nearbyLoaders") Executor loaders) {
        return new NearbyService(places, routing, limits.timeLimiter("travel-times"), properties, databaseBulkhead,
                registry, Ticker.systemTicker(), loaders);
    }
}
