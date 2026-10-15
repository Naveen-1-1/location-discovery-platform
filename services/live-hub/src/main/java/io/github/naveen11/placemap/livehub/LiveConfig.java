package io.github.naveen11.placemap.livehub;

import io.github.naveen11.placemap.concurrent.SnapshotPublisher;
import io.github.naveen11.placemap.livehub.departures.Demand;
import io.github.naveen11.placemap.livehub.departures.PollNow;
import io.github.naveen11.placemap.livehub.feeds.BluebikesFeed;
import io.github.naveen11.placemap.livehub.feeds.FeedScheduler;
import io.github.naveen11.placemap.livehub.feeds.MbtaParsers;
import io.github.naveen11.placemap.livehub.feeds.MbtaRealtimeFeed;
import io.github.naveen11.placemap.livehub.feeds.MbtaStatic;
import io.github.naveen11.placemap.livehub.feeds.MbtaStream;
import io.github.naveen11.placemap.livehub.feeds.MbtaV3;
import io.github.naveen11.placemap.livehub.sse.LiveHub;
import io.github.naveen11.placemap.livehub.state.Frame;
import io.github.naveen11.placemap.livehub.state.LiveKey;
import io.github.naveen11.placemap.livehub.state.LiveSnapshot;
import io.github.naveen11.placemap.livehub.state.SnapshotWriter;
import io.github.naveen11.placemap.livehub.state.Topic;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationPredicate;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;

/**
 * Wires live-hub (plan, showcase 2): feeds → snapshot writer → hub → SSE endpoint, started and stopped in order.
 * Every thread comes from an executor or scheduler bean below, so Spring starts and stops them all.
 */
@Configuration(proxyBeanMethods = false)
public class LiveConfig {

    private static final Logger log = LoggerFactory.getLogger(LiveConfig.class);
    private static final String USER_AGENT = "place-discovery-map-live-hub (+https://github.com/Naveen-1-1/place-discovery-map)";
    private static final Duration DRAIN_ON_SHUTDOWN = Duration.ofSeconds(5);

    /**
     * Where connections run: in {@code virtual} mode a virtual thread each, for the connection's whole life; in
     * {@code pool} mode a fixed pool that the connections' short drain jobs take turns on (L8 compares the two). The
     * pool's queue is unbounded on purpose: a connection queues at most one drain job, so connections bound it.
     */
    @Bean(defaultCandidate = false)
    TaskExecutor liveConnections(LiveProperties p, SimpleAsyncTaskExecutorBuilder virtual,
            ThreadPoolTaskExecutorBuilder pool) {
        return switch (p.connectionExecutor()) {
            case VIRTUAL -> virtual.virtualThreads(true).threadNamePrefix("live-conn-")
                    .taskTerminationTimeout(DRAIN_ON_SHUTDOWN).build();
            // Still accepts drain jobs while the context closes, so shutdown can end every open stream.
            case POOL -> pool.corePoolSize(p.poolSize()).maxPoolSize(p.poolSize()).queueCapacity(Integer.MAX_VALUE)
                    .threadNamePrefix("live-pool-").acceptTasksAfterContextClose(true).awaitTermination(true)
                    .awaitTerminationPeriod(DRAIN_ON_SHUTDOWN).build();
        };
    }

    /** The watchdog: one thread that checks every session once a tick. */
    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler liveWatchdog(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.poolSize(1).threadNamePrefix("live-watchdog-").build();
    }

    /** The polled feeds: a thread each (at most 7 with MBTA's), so one slow feed never delays another. */
    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler livePolls(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.poolSize(8).threadNamePrefix("live-poll-").build();
    }

    /** The snapshot writer's one platform thread, which owns all live state. */
    @Bean(defaultCandidate = false)
    SimpleAsyncTaskExecutor liveWriter(SimpleAsyncTaskExecutorBuilder builder) {
        return builder.virtualThreads(false).threadNamePrefix("live-snapshot-writer-").concurrencyLimit(1)
                .taskTerminationTimeout(DRAIN_ON_SHUTDOWN).build();
    }

    /** The MBTA V3 streams' readers: a virtual thread each, for as long as its stream lives. */
    @Bean(defaultCandidate = false)
    SimpleAsyncTaskExecutor mbtaStreams(SimpleAsyncTaskExecutorBuilder builder) {
        return builder.virtualThreads(true).threadNamePrefix("mbta-stream-").build();
    }

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
    LiveHub liveHub(SnapshotWriter writer, LiveProperties p, LiveMetrics metrics,
            @Qualifier("liveConnections") TaskExecutor connections,
            @Qualifier("liveWatchdog") ThreadPoolTaskScheduler watchdog) {
        var settings = new LiveHub.Settings(p.connectionExecutor(), p.mailboxCapacity(), p.maxConnections(),
                p.maxConnectionsPerClient(), p.evictAfter(), p.heartbeat(), p.watchdogTick(), p.retryMin(),
                p.retryMax());
        return new LiveHub(writer.publisher(), settings, System::nanoTime, metrics, connections, watchdog);
    }

    @Bean
    FeedScheduler feedScheduler(@Qualifier("livePolls") ThreadPoolTaskScheduler polls) {
        return new FeedScheduler(polls);
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
            RestClient.Builder http, LiveMetrics metrics, Demand departuresDemand, PollNow departuresPollNow,
            @Qualifier("liveWriter") AsyncTaskExecutor writerThread,
            @Qualifier("livePolls") ThreadPoolTaskScheduler polls, @Qualifier("mbtaStreams") Executor streamReaders) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                // A thread for each feed from the start, so live-hub's thread count is flat however feeds behave.
                polls.getScheduledThreadPoolExecutor().prestartAllCoreThreads();
                writer.start(writerThread);
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
                    // With a key, the V3 streams lead and each open file is read only while its stream is down.
                    BooleanSupplier alertsFallback = () -> true;
                    BooleanSupplier vehiclesFallback = () -> true;
                    if (!mbta.apiKey().isBlank()) {
                        MbtaStream alerts = stream(mbta, "/alerts", Topic.ALERTS, MbtaV3.alerts(names::names),
                                mbta.alertsIdle());
                        MbtaStream vehicles = stream(mbta, "/vehicles", Topic.VEHICLES, MbtaV3.vehicles(),
                                mbta.vehiclesIdle());
                        alertsFallback = () -> !alerts.healthy();
                        vehiclesFallback = () -> !vehicles.healthy();
                    } else {
                        log.info("MBTA_API_KEY is empty: MBTA alerts and vehicles come from the open realtime files");
                    }
                    scheduler.start(new MbtaRealtimeFeed(client, mbta.realtimeUrl() + "Alerts_enhanced.json",
                            Topic.ALERTS, mbta.alertsInterval(), MbtaParsers.alerts(names::names), alertsFallback,
                            writer, metrics, System::currentTimeMillis));
                    scheduler.start(new MbtaRealtimeFeed(client, mbta.realtimeUrl() + "VehiclePositions_enhanced.json",
                            Topic.VEHICLES, mbta.vehiclesInterval(), MbtaParsers.vehicles(), vehiclesFallback,
                            writer, metrics, System::currentTimeMillis));
                    departuresPollNow.connect(scheduler.start(new MbtaRealtimeFeed(client,
                            mbta.realtimeUrl() + "TripUpdates_enhanced.json", Topic.DEPARTURES,
                            mbta.departuresInterval(), MbtaParsers.departures(names::names), departuresDemand::recent,
                            writer, metrics, System::currentTimeMillis)));
                } else {
                    log.warn("MBTA feeds disabled (placemap.live.mbta.enabled=false)");
                }
                running = true;
            }

            private final List<MbtaStream> streams = new ArrayList<>();

            private MbtaStream stream(LiveProperties.Mbta mbta, String path, Topic topic, MbtaStream.Handler handler,
                    Duration idle) {
                var stream = new MbtaStream(URI.create(mbta.v3Url() + path), mbta.apiKey(), topic, handler, writer,
                        metrics, mbta.streamTick(), idle, System::currentTimeMillis);
                stream.start(streamReaders);
                scheduler.start(stream); // its tick: "still connected"
                streams.add(stream);
                return stream;
            }

            @Override
            public void stop() {
                polls.shutdown();
                streams.forEach(MbtaStream::close);
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
