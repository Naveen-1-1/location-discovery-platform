package io.github.naveen11.placemap.livehub.sse;

import io.github.naveen11.placemap.concurrent.SnapshotPublisher;
import io.github.naveen11.placemap.livehub.LiveMetrics;
import io.github.naveen11.placemap.livehub.state.Frame;
import io.github.naveen11.placemap.livehub.state.LiveKey;
import io.github.naveen11.placemap.livehub.state.LiveSnapshot;
import io.github.naveen11.placemap.livehub.state.Topic;
import java.io.Serial;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * All open SSE sessions (ADR 0018, 0023, 0041): admits new ones within the connection limits, starts each one's drain
 * on {@code connectionExecutor}, and runs the 1 s watchdog that evicts stuck clients and queues heartbeats.
 */
public final class LiveHub implements AutoCloseable {

    /** Why a connection was refused. */
    public enum Refusal {
        HUB_FULL, CLIENT_FULL, CLOSING
    }

    /** Thrown by {@link #open} when the connection may not be opened. */
    public static final class RefusedException extends Exception {
        @Serial
        private static final long serialVersionUID = 1L;

        private final Refusal refusal;

        RefusedException(Refusal refusal) {
            super(refusal.name(), null, false, false);
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    /** The limits and timings from {@code placemap.live}. */
    public record Settings(ConnectionMode mode, int poolSize, int mailboxCapacity, int maxConnections,
            int maxConnectionsPerClient, Duration evictAfter, Duration heartbeat, Duration watchdogTick,
            Duration retryMin, Duration retryMax) {
    }

    private static final Logger log = LoggerFactory.getLogger(LiveHub.class);

    private final SnapshotPublisher<LiveSnapshot, LiveKey, Frame> publisher;
    private final Settings settings;
    private final LongSupplier nanoClock;
    private final LiveMetrics metrics;
    private final ExecutorService connections;
    private final ScheduledExecutorService watchdog;
    private final Set<ClientSession> sessions = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, AtomicInteger> perClient = new ConcurrentHashMap<>();
    private final AtomicInteger open = new AtomicInteger();
    private volatile boolean closing;

    public LiveHub(SnapshotPublisher<LiveSnapshot, LiveKey, Frame> publisher, Settings settings, LongSupplier nanoClock,
            LiveMetrics metrics) {
        this.publisher = publisher;
        this.settings = settings;
        this.nanoClock = nanoClock;
        this.metrics = metrics;
        this.connections = switch (settings.mode()) {
            case VIRTUAL -> Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("live-conn-", 0).factory());
            // Unbounded queue on purpose: SerialDrain queues at most one job per session, so sessions bound it.
            case POOL -> new ThreadPoolExecutor(settings.poolSize(), settings.poolSize(), 0, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(), Thread.ofPlatform().name("live-pool-", 0).factory());
        };
        this.watchdog = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("live-watchdog").daemon().factory());
        long tick = settings.watchdogTick().toMillis();
        watchdog.scheduleWithFixedDelay(this::checkAll, tick, tick, TimeUnit.MILLISECONDS);
        metrics.gauge("placemap.live.sessions", "Open SSE connections", open, AtomicInteger::get);
        log.info("live hub: {} connections, at most {} ({} per client)", settings.mode(), settings.maxConnections(),
                settings.maxConnectionsPerClient());
    }

    /**
     * Opens a session for {@code client} (a salted hash, never an address) and starts sending it the snapshot.
     *
     * @throws RefusedException if a limit is reached or live-hub is shutting down; nothing was opened
     */
    public ClientSession open(String client, Set<Topic> topics, EventSink sink) throws RefusedException {
        if (closing) {
            throw refuse(Refusal.CLOSING);
        }
        if (open.incrementAndGet() > settings.maxConnections()) {
            open.decrementAndGet();
            throw refuse(Refusal.HUB_FULL);
        }
        if (!reserve(client)) {
            open.decrementAndGet();
            throw refuse(Refusal.CLIENT_FULL);
        }
        long retry = ThreadLocalRandom.current().nextLong(settings.retryMin().toMillis(), settings.retryMax().toMillis() + 1);
        var session = new ClientSession(client, topics, settings.mailboxCapacity(), sink, publisher::current, retry,
                nanoClock, metrics, this::closed);
        sessions.add(session);
        publisher.subscribe(session); // before the drain reads the snapshot (ADR 0023)
        try {
            switch (settings.mode()) {
                case VIRTUAL -> connections.execute(session::runLoop);
                case POOL -> session.startOn(connections);
            }
        } catch (RejectedExecutionException e) {
            session.close(ClientSession.End.SHUTDOWN);
            throw refuse(Refusal.CLOSING);
        }
        metrics.connection("accepted");
        return session;
    }

    public int openSessions() {
        return open.get();
    }

    /** Evicts stuck sessions and queues heartbeats; runs on the watchdog thread. Package-private for tests. */
    void checkAll() {
        long evict = settings.evictAfter().toNanos();
        long heartbeat = settings.heartbeat().toNanos();
        for (ClientSession session : sessions) {
            try {
                session.check(evict, heartbeat);
            } catch (RuntimeException e) {
                log.error("watchdog check failed for a session", e); // never let one session stop the watchdog
            }
        }
    }

    @Override
    public void close() {
        closing = true;
        watchdog.shutdownNow();
        for (ClientSession session : sessions) {
            session.close(ClientSession.End.SHUTDOWN);
        }
        connections.shutdown();
        try {
            if (!connections.awaitTermination(5, TimeUnit.SECONDS)) {
                connections.shutdownNow();
            }
        } catch (InterruptedException e) {
            connections.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** The session's one-time cleanup, run by whichever close won. */
    private void closed(ClientSession session) {
        sessions.remove(session);
        publisher.unsubscribe(session);
        perClient.computeIfPresent(session.client(), (k, count) -> count.decrementAndGet() == 0 ? null : count);
        open.decrementAndGet();
        metrics.sessionClosed(session.end().label());
    }

    private boolean reserve(String client) {
        var granted = new boolean[1];
        perClient.compute(client, (k, count) -> {
            AtomicInteger c = count == null ? new AtomicInteger() : count;
            if (c.get() < settings.maxConnectionsPerClient()) {
                c.incrementAndGet();
                granted[0] = true;
            }
            return c.get() == 0 ? null : c;
        });
        return granted[0];
    }

    private RefusedException refuse(Refusal refusal) {
        metrics.connection(refusal.name().toLowerCase(java.util.Locale.ROOT));
        return new RefusedException(refusal);
    }
}
