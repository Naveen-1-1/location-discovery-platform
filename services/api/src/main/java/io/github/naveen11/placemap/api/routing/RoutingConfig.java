package io.github.naveen11.placemap.api.routing;

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

/** Wires the routing engine: openrouteservice when ORS_API_KEY is set, otherwise a client that always falls back. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RoutingProperties.class)
class RoutingConfig {

    private static final Logger log = LoggerFactory.getLogger(RoutingConfig.class);

    @Bean
    RoutingClient routingClient(RoutingProperties properties, RestClient.Builder builder) {
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
