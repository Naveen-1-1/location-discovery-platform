package io.github.naveen11.placemap.api.ratelimit;

import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Rate limits for everything under /api/ (not /actuator/), after the filter that records request metrics. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnBooleanProperty(name = "placemap.rate-limit.enabled", matchIfMissing = true)
class RateLimitConfig {

    @Bean
    ClientRateLimiter clientRateLimiter(RateLimitProperties properties) {
        return new ClientRateLimiter(properties, TimeMeter.SYSTEM_NANOTIME);
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(ClientRateLimiter limiter, MeterRegistry registry) {
        var filter = new RateLimitFilter(limiter, registry.counter("placemap.ratelimit.rejected"));
        var registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        // 429s still count in http.server.requests: Spring's observation filter runs at HIGHEST_PRECEDENCE + 1.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
