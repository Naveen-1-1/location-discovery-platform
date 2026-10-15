package io.github.naveen11.placemap.api.directions;

import io.github.naveen11.placemap.api.places.PlaceRepository;
import io.github.naveen11.placemap.api.routing.GuardedRouting;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DirectionsProperties.class)
class DirectionsConfig {

    /** Shared route and isochrone loads, one virtual thread each. */
    @Bean(defaultCandidate = false)
    SimpleAsyncTaskExecutor directionsLoaders(SimpleAsyncTaskExecutorBuilder builder) {
        return builder.threadNamePrefix("directions-load-").taskTerminationTimeout(Duration.ofSeconds(5)).build();
    }

    @Bean
    DirectionsService directionsService(PlaceRepository places, GuardedRouting routing, TimeLimiterRegistry limits,
            DirectionsProperties properties, MeterRegistry registry, @Qualifier("directionsLoaders") Executor loaders) {
        return new DirectionsService(places, routing, limits.timeLimiter("directions"), properties, registry, loaders);
    }
}
