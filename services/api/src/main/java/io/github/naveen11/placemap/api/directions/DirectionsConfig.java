package io.github.naveen11.placemap.api.directions;

import io.github.naveen11.placemap.api.places.PlaceRepository;
import io.github.naveen11.placemap.api.routing.GuardedRouting;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DirectionsProperties.class)
class DirectionsConfig {

    @Bean
    DirectionsService directionsService(PlaceRepository places, GuardedRouting routing, DirectionsProperties properties,
            MeterRegistry registry) {
        return new DirectionsService(places, routing, properties, registry);
    }
}
