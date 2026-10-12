package io.github.naveen11.placemap.api.bodylimit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.unit.DataSize;

/** The body limit for every path, right after the filter that records request metrics, so refusals are counted. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BodyLimitConfig.BodyLimitProperties.class)
class BodyLimitConfig {

    /**
     * {@code placemap.body-limit.*} (ADR 0044).
     *
     * @param max the largest request body the api accepts; it reads none, so this only spares harmless clients
     */
    @ConfigurationProperties("placemap.body-limit")
    record BodyLimitProperties(@DefaultValue("1KB") DataSize max) {
    }

    @Bean
    FilterRegistrationBean<BodyLimitFilter> bodyLimitFilter(BodyLimitProperties properties, MeterRegistry registry) {
        var filter = new BodyLimitFilter(properties.max().toBytes(), registry.counter("placemap.bodylimit.rejected"));
        var registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        // Spring's observation filter runs at HIGHEST_PRECEDENCE + 1; the rate limiter at + 10.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        return registration;
    }
}
