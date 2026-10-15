package io.github.naveen11.placemap.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/** Telemetry beans every placemap service needs. Each service still has its own logback-spring.xml with the OTEL appender. */
@AutoConfiguration
public class ObservabilityAutoConfiguration {

    /** Connects the OTEL Logback appender to Spring Boot's OpenTelemetry SDK so logs go out over OTLP. */
    @Bean
    InitializingBean openTelemetryAppenderInstaller(ObjectProvider<OpenTelemetry> openTelemetry) {
        return () -> openTelemetry.ifAvailable(OpenTelemetryAppender::install);
    }
}
