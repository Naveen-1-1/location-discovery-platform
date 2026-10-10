package io.github.naveen11.placemap.api.observability;

import io.micrometer.java21.instrument.binder.jdk.VirtualThreadMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes jvm.threads.virtual.pinned (a virtual thread blocked while pinned to its carrier) and
 * jvm.threads.virtual.submit.failed, read from JFR events. Pinning is the main way virtual threads lose their
 * scalability, so the JVM/threads dashboard alerts on it.
 */
@Configuration(proxyBeanMethods = false)
class VirtualThreadMetricsConfig {

    @Bean(destroyMethod = "close")
    VirtualThreadMetrics virtualThreadMetrics() {
        return new VirtualThreadMetrics();
    }
}
