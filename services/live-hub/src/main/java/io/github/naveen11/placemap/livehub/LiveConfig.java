package io.github.naveen11.placemap.livehub;

import io.github.naveen11.placemap.concurrent.SnapshotPublisher;
import io.github.naveen11.placemap.livehub.feeds.BluebikesFeed;
import io.github.naveen11.placemap.livehub.feeds.FeedScheduler;
import io.github.naveen11.placemap.livehub.sse.LiveHub;
import io.github.naveen11.placemap.livehub.sse.LiveServlet;
import io.github.naveen11.placemap.livehub.state.Frame;
import io.github.naveen11.placemap.livehub.state.LiveKey;
import io.github.naveen11.placemap.livehub.state.LiveSnapshot;
import io.github.naveen11.placemap.livehub.state.SnapshotWriter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationPredicate;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.web.client.RestClient;

/** Wires live-hub (plan, showcase 2): feeds → snapshot writer → hub → SSE servlet, started and stopped in order. */
@Configuration(proxyBeanMethods = false)
public class LiveConfig {

    private static final Logger log = LoggerFactory.getLogger(LiveConfig.class);

    @Bean
    LiveMetrics liveMetrics(MeterRegistry registry) {
        return new LiveMetrics(registry);
    }

    @Bean
    SnapshotWriter snapshotWriter(LiveProperties properties, LiveMetrics metrics) {
        return new SnapshotWriter(properties.queueCapacity(), properties.submitWait(), properties.tick(),
                properties.staleAfterTtls(), System::currentTimeMillis, System::nanoTime, metrics);
    }

    @Bean
    SnapshotPublisher<LiveSnapshot, LiveKey, Frame> snapshotPublisher(SnapshotWriter writer) {
        return writer.publisher();
    }

    @Bean
    LiveHub liveHub(SnapshotWriter writer, LiveProperties p, LiveMetrics metrics) {
        var settings = new LiveHub.Settings(p.connectionExecutor(), p.poolSize(), p.mailboxCapacity(),
                p.maxConnections(), p.maxConnectionsPerClient(), p.evictAfter(), p.heartbeat(), p.watchdogTick(),
                p.retryMin(), p.retryMax());
        return new LiveHub(writer.publisher(), settings, System::nanoTime, metrics);
    }

    @Bean
    ServletRegistrationBean<LiveServlet> liveServlet(LiveHub hub) {
        var registration = new ServletRegistrationBean<>(new LiveServlet(hub), "/live");
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean
    FeedScheduler feedScheduler() {
        return new FeedScheduler();
    }

    /**
     * Starts the writer and the feeds once the web server is up, and on shutdown stops them and closes every stream
     * first: an open stream never ends by itself, so graceful shutdown would otherwise wait out its timeout.
     */
    @Bean
    SmartLifecycle liveLifecycle(SnapshotWriter writer, FeedScheduler scheduler, LiveHub hub, LiveProperties p,
            RestClient.Builder http, LiveMetrics metrics) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                writer.start();
                LiveProperties.Bluebikes bluebikes = p.bluebikes();
                if (bluebikes.enabled()) {
                    RestClient client = http.clone()
                            .requestFactory(jdk(bluebikes.connectTimeout(), bluebikes.timeout()))
                            .defaultHeader("User-Agent", "place-discovery-map-live-hub (+https://github.com/Naveen-1-1/place-discovery-map)")
                            .build();
                    scheduler.start(new BluebikesFeed(client, bluebikes.url(), bluebikes.minInterval(),
                            bluebikes.maxInterval(), writer, metrics, System::currentTimeMillis));
                } else {
                    log.warn("bluebikes feed disabled (placemap.live.bluebikes.enabled=false)");
                }
                running = true;
            }

            @Override
            public void stop() {
                scheduler.close();
                hub.close();
                writer.close();
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public int getPhase() {
                return DEFAULT_PHASE; // after the web server starts, and stopped before graceful shutdown waits
            }
        };
    }

    /** SSE streams last for minutes or hours: as traces or request timings they would only be noise. */
    @Bean
    ObservationPredicate noObservationsForLiveStreams() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext request
                && request.getCarrier().getRequestURI().startsWith("/live"));
    }

    static JdkClientHttpRequestFactory jdk(Duration connectTimeout, Duration timeout) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(timeout);
        return factory;
    }
}
