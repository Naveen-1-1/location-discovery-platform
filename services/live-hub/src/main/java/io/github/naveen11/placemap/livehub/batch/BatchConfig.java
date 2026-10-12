package io.github.naveen11.placemap.livehub.batch;

import io.github.naveen11.placemap.livehub.LiveProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** The body cap for every path, right after the filter that records request metrics, so refusals are counted. */
@Configuration(proxyBeanMethods = false)
class BatchConfig {

    @Bean
    FilterRegistrationBean<BodyLimitFilter> bodyLimitFilter(LiveProperties properties) {
        var registration = new FilterRegistrationBean<>(new BodyLimitFilter(properties.maxBody().toBytes()));
        registration.addUrlPatterns("/*");
        // Spring's observation filter runs at HIGHEST_PRECEDENCE + 1.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        return registration;
    }
}
