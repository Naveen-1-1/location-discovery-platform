package io.github.naveen11.placemap.api.search;

import io.github.naveen11.placemap.api.places.PlaceRepository;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Wires the search box: Photon behind its own bulkhead, names behind the database's. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchProperties.class)
class SearchConfig {

    @Bean
    PhotonClient photonClient(SearchProperties properties, RestClient.Builder builder) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.photonTimeout()).build());
        factory.setReadTimeout(properties.photonTimeout());
        RestClient http = builder.clone()
                .baseUrl(properties.photonUrl().toString())
                .requestFactory(factory)
                .defaultHeader("User-Agent", "place-discovery-map (+https://github.com/Naveen-1-1/place-discovery-map)")
                .observationConvention(new PhotonClient.QueryFreeUrls())
                .build();
        return new PhotonClient(http, properties.bbox());
    }

    @Bean
    SearchService searchService(PlaceRepository places, PhotonClient photon, Bulkhead databaseBulkhead,
            SearchProperties properties, MeterRegistry registry) {
        // Full means answered at once without addresses: waiting would only push the search past its deadline.
        Bulkhead photonCalls = Bulkhead.of("photon", BulkheadConfig.custom()
                .maxConcurrentCalls(properties.photonConcurrency())
                .maxWaitDuration(Duration.ZERO)
                .build());
        return new SearchService(places, photon, databaseBulkhead, photonCalls, properties, registry);
    }
}
