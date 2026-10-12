package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.ingest.IngestProperties;
import io.github.naveen11.placemap.ingest.catalog.SourceCatalog;
import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.source.SourceAdapter;
import io.github.naveen11.placemap.ingest.source.SourceAdapters;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The importer's own timetable, written with plain {@code java.util.concurrent} instead of
 * {@code @Scheduled}.
 *
 * <ul>
 *   <li>One platform thread ticks every minute and only decides what is due: a source with an unfinished run (resume),
 *       one whose last success is older than its cadence, or one whose last failure is older than the retry delay.
 *   <li>Each run gets its own virtual thread; a semaphore caps how many run at once (each holds several database
 *       connections).
 *   <li>Shutdown (SIGTERM): stop reading, let in-flight batches commit, and after the grace period cancel whatever
 *       is left; the batch in flight rolls back and is redone on resume.
 * </ul>
 */
public class IngestScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IngestScheduler.class);

    private final IngestProperties properties;
    private final SourceCatalog catalog;
    private final SourceAdapters adapters;
    private final RunCoordinator coordinator;
    private final RunStore runs;
    private final ScheduledExecutorService ticker =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("ingest-scheduler").factory());
    private final ExecutorService runThreads =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("ingest-run-", 1).factory());
    private final Semaphore slots;
    private final Map<String, RunControl> active = new ConcurrentHashMap<>();
    private final Set<String> reportedNotReady = ConcurrentHashMap.newKeySet();
    private volatile boolean running;

    public IngestScheduler(IngestProperties properties, SourceCatalog catalog, SourceAdapters adapters,
            RunCoordinator coordinator, RunStore runs) {
        this.properties = properties;
        this.catalog = catalog;
        this.adapters = adapters;
        this.coordinator = coordinator;
        this.runs = runs;
        this.slots = new Semaphore(properties.maxConcurrentRuns());
    }

    @Override
    public void start() {
        running = true;
        if (properties.onceMode() || properties.toolMode()) {
            return; // the main thread drives the single run or the tool (IngestApplication)
        }
        log.info("ingest scheduler: checking {} every {}", adapters.all().stream().map(SourceAdapter::sourceId).toList(),
                properties.tick());
        ticker.scheduleWithFixedDelay(this::tick, 0, properties.tick().toMillis(), TimeUnit.MILLISECONDS);
    }

    void tick() {
        // An exception escaping a scheduled task silently cancels all its future runs, so catch everything.
        for (SourceAdapter adapter : adapters.all()) {
            try {
                maybeLaunch(adapter.sourceId());
            } catch (RuntimeException e) {
                log.error("ingest scheduler: could not check {}", adapter.sourceId(), e);
            }
        }
    }

    private void maybeLaunch(String sourceId) {
        if (!running || active.containsKey(sourceId) || !isDue(sourceId)) {
            return;
        }
        if (!slots.tryAcquire()) {
            log.debug("ingest scheduler: {} is due but {} runs are already active", sourceId, slots.availablePermits());
            return;
        }
        var control = new RunControl();
        if (active.putIfAbsent(sourceId, control) != null) {
            slots.release();
            return;
        }
        runThreads.execute(() -> {
            try {
                coordinator.run(sourceId, control, properties.force());
            } finally {
                active.remove(sourceId);
                slots.release();
            }
        });
    }

    boolean isDue(String sourceId) {
        SourceDefinition source = catalog.get(sourceId);
        if (!source.isEnabled()) {
            return false;
        }
        Optional<String> notReady = adapters.get(sourceId).notReady(source);
        if (notReady.isPresent()) {
            if (reportedNotReady.add(sourceId)) {
                log.warn("ingest scheduler: skipping {}: {}", sourceId, notReady.get());
            }
            return false;
        }
        reportedNotReady.remove(sourceId);
        if (runs.findOpen(sourceId).isPresent()) {
            return true; // resume
        }
        Optional<RunRecord> last = runs.latestFinished(sourceId);
        if (last.isEmpty()) {
            return true;
        }
        OffsetDateTime finished = last.get().finishedAt();
        var wait = RunRecord.FAILED.equals(last.get().status()) ? properties.retryFailedAfter() : source.refreshInterval();
        return finished == null || !finished.plus(wait).isAfter(OffsetDateTime.now());
    }

    /** Once mode: run or resume one source on the calling thread. */
    public RunCoordinator.Outcome runOnce(String sourceId) {
        var control = new RunControl();
        active.put(sourceId, control);
        try {
            return coordinator.run(sourceId, control, properties.force());
        } finally {
            active.remove(sourceId);
        }
    }

    @Override
    public void stop() {
        running = false;
        ticker.shutdownNow();
        active.values().forEach(RunControl::requestStop);
        long deadline = System.nanoTime() + properties.shutdownGrace().toNanos();
        try {
            while (!active.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            if (!active.isEmpty()) {
                log.warn("ingest scheduler: {} still running after {}; cancelling", active.keySet(),
                        properties.shutdownGrace());
                active.values().forEach(RunControl::cancel);
            }
            runThreads.shutdown();
            runThreads.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
