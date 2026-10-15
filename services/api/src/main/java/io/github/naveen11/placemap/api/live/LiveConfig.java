package io.github.naveen11.placemap.api.live;

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

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LiveProperties.class)
class LiveConfig {

    /** Batch calls to live-hub, one virtual thread each, so a search can stop waiting at its budget. */
    @Bean(defaultCandidate = false)
    SimpleAsyncTaskExecutor liveCalls(SimpleAsyncTaskExecutorBuilder builder) {
        return builder.threadNamePrefix("live-batch-").taskTerminationTimeout(Duration.ofSeconds(5)).build();
    }

    @Bean
    LiveHubClient liveHubClient(LiveProperties properties, RestClient.Builder builder, MeterRegistry registry,
            @Qualifier("liveCalls") Executor calls) {
        if (properties.url().isBlank()) {
            return new LiveHubClient(null, properties.timeout(), registry, calls);
        }
        // The HTTP timeouts only end calls the client has already stopped waiting for; fetch() enforces the budget.
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
        factory.setReadTimeout(properties.timeout());
        RestClient http = builder.clone().baseUrl(properties.url()).requestFactory(factory).build();
        return new LiveHubClient(http, properties.timeout(), registry, calls);
    }
}
