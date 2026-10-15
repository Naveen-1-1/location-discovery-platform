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
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;

/**
 * All open SSE sessions: admits new ones within the connection limits, starts each one's drain on the connection
 * executor, and runs the 1 s watchdog that evicts stuck clients and queues heartbeats. Both the executor and the
 * watchdog's scheduler are Spring's, which starts and stops their threads.
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
    public record Settings(ConnectionMode mode, int mailboxCapacity, int maxConnections, int maxConnectionsPerClient,
            Duration evictAfter, Duration heartbeat, Duration watchdogTick, Duration retryMin, Duration retryMax) {
    }

    private static final Logger log = LoggerFactory.getLogger(LiveHub.class);

    private final SnapshotPublisher<LiveSnapshot, LiveKey, Frame> publisher;
    private final Settings settings;
    private final LongSupplier nanoClock;
    private final LiveMetrics metrics;
    private final Executor connections;
    private final ScheduledFuture<?> watchdog;
    private final Set<ClientSession> sessions = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, AtomicInteger> perClient = new ConcurrentHashMap<>();
    private final AtomicInteger open = new AtomicInteger();
    private volatile boolean closing;

    /**
     * @param connections in {@link ConnectionMode#VIRTUAL} a thread per task (each runs a connection's whole life); in
     *                    {@link ConnectionMode#POOL} a fixed pool that the connections' short drain jobs share
     * @param watchdog    runs {@link #checkAll()} every {@code watchdogTick}
     */
    public LiveHub(SnapshotPublisher<LiveSnapshot, LiveKey, Frame> publisher, Settings settings, LongSupplier nanoClock,
            LiveMetrics metrics, Executor connections, TaskScheduler watchdog) {
        this.publisher = publisher;
        this.settings = settings;
        this.nanoClock = nanoClock;
        this.metrics = metrics;
        this.connections = connections;
        this.watchdog = watchdog.scheduleWithFixedDelay(this::checkAll, settings.watchdogTick());
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
        publisher.subscribe(session); // before the drain reads the snapshot
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

    /** Refuses new streams and closes every open one; the executor then lets their drains end the responses. */
    @Override
    public void close() {
        closing = true;
        watchdog.cancel(false);
        for (ClientSession session : sessions) {
            session.close(ClientSession.End.SHUTDOWN);
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
