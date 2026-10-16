package io.github.naveen11.placemap.livehub;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.tomcat.TomcatConnectorCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tomcat sized for thousands of long-lived event streams. Every open connection keeps its socket buffers for as long
 * as it stays open, 8 KB each way by default. A stream's request is one small GET and each of its writes is a batch
 * of at most a few KB, so smaller buffers serve it as well; ordinary requests (the api's batch lookups) are only read
 * and written in more pieces. The header limits, kept per connection too, are in application.yaml.
 */
@Configuration(proxyBeanMethods = false)
class TomcatConfig {

    /** Bytes read from a connection at a time. */
    static final int READ_BUFFER = 2 * 1024;

    /** Bytes collected before a write to a connection. */
    static final int WRITE_BUFFER = 4 * 1024;

    @Bean
    TomcatConnectorCustomizer smallConnectionBuffers() {
        return connector -> {
            set(connector, "socket.appReadBufSize", READ_BUFFER);
            set(connector, "socket.appWriteBufSize", WRITE_BUFFER);
        };
    }

    private static void set(Connector connector, String name, int value) {
        if (!connector.setProperty(name, String.valueOf(value))) {
            throw new IllegalStateException("Tomcat's connector has no property " + name);
        }
    }
}
