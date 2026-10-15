package io.github.naveen11.placemap.api.search;

import io.github.naveen11.placemap.api.places.PlaceRepository;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Wires the search box: Photon behind its own bulkhead, names behind the database's (resilience4j.* settings). */
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

    /** Photon calls, one virtual thread each, so the search can stop waiting at its deadline. */
    @Bean(defaultCandidate = false)
    SimpleAsyncTaskExecutor photonCalls(SimpleAsyncTaskExecutorBuilder builder) {
        return builder.threadNamePrefix("search-photon-").taskTerminationTimeout(Duration.ofSeconds(5)).build();
    }

    @Bean
    SearchService searchService(PlaceRepository places, PhotonClient photon, Bulkhead databaseBulkhead,
            BulkheadRegistry bulkheads, SearchProperties properties, MeterRegistry registry,
            @Qualifier("photonCalls") Executor photonCalls) {
        return new SearchService(places, photon, databaseBulkhead, bulkheads.bulkhead("photon"), properties, registry,
                photonCalls);
    }
}
