package io.github.naveen11.placemap.livehub;

import io.github.naveen11.placemap.concurrent.SnapshotPublisher;
import io.github.naveen11.placemap.livehub.departures.Demand;
import io.github.naveen11.placemap.livehub.departures.PollNow;
import io.github.naveen11.placemap.livehub.feeds.BluebikesFeed;
import io.github.naveen11.placemap.livehub.feeds.FeedScheduler;
import io.github.naveen11.placemap.livehub.feeds.MbtaParsers;
import io.github.naveen11.placemap.livehub.feeds.MbtaRealtimeFeed;
import io.github.naveen11.placemap.livehub.feeds.MbtaStatic;
import io.github.naveen11.placemap.livehub.sse.LiveHub;
import io.github.naveen11.placemap.livehub.sse.LiveServlet;
import io.github.naveen11.placemap.livehub.state.Frame;
import io.github.naveen11.placemap.livehub.state.LiveKey;
import io.github.naveen11.placemap.livehub.state.LiveSnapshot;
import io.github.naveen11.placemap.livehub.state.SnapshotWriter;
import io.github.naveen11.placemap.livehub.state.Topic;
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
    private static final String USER_AGENT = "place-discovery-map-live-hub (+https://github.com/Naveen-1-1/place-discovery-map)";

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

    @Bean
    Demand departuresDemand(LiveProperties properties) {
        return new Demand(properties.mbta().departuresDemand(), System::currentTimeMillis);
    }

    @Bean
    PollNow departuresPollNow() {
        return new PollNow();
    }

    /**
     * Starts the writer and the feeds once the web server is up, and on shutdown stops them and closes every stream
     * first: an open stream never ends by itself, so graceful shutdown would otherwise wait out its timeout.
     */
    @Bean
    SmartLifecycle liveLifecycle(SnapshotWriter writer, FeedScheduler scheduler, LiveHub hub, LiveProperties p,
            RestClient.Builder http, LiveMetrics metrics, Demand departuresDemand, PollNow departuresPollNow) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                writer.start();
                LiveProperties.Bluebikes bluebikes = p.bluebikes();
                if (bluebikes.enabled()) {
                    RestClient client = http.clone()
                            .requestFactory(jdk(bluebikes.connectTimeout(), bluebikes.timeout()))
                            .defaultHeader("User-Agent", USER_AGENT)
                            .build();
                    scheduler.start(new BluebikesFeed(client, bluebikes.url(), bluebikes.minInterval(),
                            bluebikes.maxInterval(), writer, metrics, System::currentTimeMillis));
                } else {
                    log.warn("bluebikes feed disabled (placemap.live.bluebikes.enabled=false)");
                }
                LiveProperties.Mbta mbta = p.mbta();
                if (mbta.enabled()) {
                    RestClient client = http.clone()
                            .requestFactory(jdk(mbta.connectTimeout(), mbta.timeout()))
                            .defaultHeader("User-Agent", USER_AGENT)
                            .build();
                    var names = new MbtaStatic(client, mbta.gtfsUrl());
                    scheduler.start(names);
                    scheduler.start(new MbtaRealtimeFeed(client, mbta.realtimeUrl() + "Alerts_enhanced.json",
                            Topic.ALERTS, mbta.alertsInterval(), MbtaParsers.alerts(names::names), () -> true, writer,
                            metrics, System::currentTimeMillis));
                    scheduler.start(new MbtaRealtimeFeed(client, mbta.realtimeUrl() + "VehiclePositions_enhanced.json",
                            Topic.VEHICLES, mbta.vehiclesInterval(), MbtaParsers.vehicles(), () -> true, writer,
                            metrics, System::currentTimeMillis));
                    departuresPollNow.connect(scheduler.start(new MbtaRealtimeFeed(client,
                            mbta.realtimeUrl() + "TripUpdates_enhanced.json", Topic.DEPARTURES,
                            mbta.departuresInterval(), MbtaParsers.departures(names::names), departuresDemand::recent,
                            writer, metrics, System::currentTimeMillis)));
                } else {
                    log.warn("MBTA feeds disabled (placemap.live.mbta.enabled=false)");
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
                && request.getCarrier().getRequestURI().equals("/live"));
    }

    static JdkClientHttpRequestFactory jdk(Duration connectTimeout, Duration timeout) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(timeout);
        return factory;
    }
}
