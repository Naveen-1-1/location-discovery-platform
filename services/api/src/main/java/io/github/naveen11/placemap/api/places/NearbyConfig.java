package io.github.naveen11.placemap.api.places;

import com.github.benmanes.caffeine.cache.Ticker;
import io.github.naveen11.placemap.api.routing.GuardedRouting;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires nearby search with its database bulkhead (ADR 0026), exported as metrics. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NearbyProperties.class)
class NearbyConfig {

    @Bean
    NearbyService nearbyService(PlaceRepository places, GuardedRouting routing, NearbyProperties properties,
            MeterRegistry registry) {
        var bulkheads = BulkheadRegistry.ofDefaults();
        // Full means rejected at once: waiting would only push the request past its deadline.
        var database = bulkheads.bulkhead("database", BulkheadConfig.custom()
                .maxConcurrentCalls(properties.databaseConcurrency())
                .maxWaitDuration(Duration.ZERO)
                .build());
        TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheads).bindTo(registry);
        return new NearbyService(places, routing, properties, database, registry, Ticker.systemTicker());
    }
}
