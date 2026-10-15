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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;

/**
 * The importer's timetable.
 *
 * <ul>
 *   <li>A tick every minute ({@code ticks}, one platform thread) only decides what is due: a source with an unfinished
 *       run (resume), one whose last success is older than its cadence, or one whose last failure is older than the
 *       retry delay.
 *   <li>Each run gets a thread of {@code runs} (virtual), which refuses more runs than {@code max-concurrent-runs}
 *       (each holds several database connections); a source refused now is due again on the next tick.
 *   <li>Shutdown (SIGTERM): stop each import after the batches it is committing, wait up to the grace period, then
 *       interrupt what is left; a batch in flight rolls back and is redone on resume.
 * </ul>
 */
public class IngestScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IngestScheduler.class);

    private final IngestProperties properties;
    private final SourceCatalog catalog;
    private final SourceAdapters adapters;
    private final RunCoordinator coordinator;
    private final RunStore runs;
    private final TaskScheduler ticks;
    private final AsyncTaskExecutor runThreads;
    private final Map<String, Future<?>> active = new ConcurrentHashMap<>();
    private final Set<String> reportedNotReady = ConcurrentHashMap.newKeySet();
    private volatile ScheduledFuture<?> ticking;
    private volatile boolean running;

    public IngestScheduler(IngestProperties properties, SourceCatalog catalog, SourceAdapters adapters,
            RunCoordinator coordinator, RunStore runs, TaskScheduler ticks, AsyncTaskExecutor runThreads) {
        this.properties = properties;
        this.catalog = catalog;
        this.adapters = adapters;
        this.coordinator = coordinator;
        this.runs = runs;
        this.ticks = ticks;
        this.runThreads = runThreads;
    }

    @Override
    public void start() {
        running = true;
        if (properties.onceMode() || properties.toolMode()) {
            return; // the main thread drives the single run or the tool (IngestRunner)
        }
        log.info("ingest scheduler: checking {} every {}", adapters.all().stream().map(SourceAdapter::sourceId).toList(),
                properties.tick());
        ticking = ticks.scheduleWithFixedDelay(this::tick, properties.tick());
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
        try {
            // The map holds the key while the run is submitted, so the run's own removal can never come first.
            active.computeIfAbsent(sourceId, id -> runThreads.submit(() -> {
                try {
                    coordinator.run(id, properties.force());
                } finally {
                    active.remove(id);
                }
            }));
        } catch (TaskRejectedException e) {
            log.debug("ingest scheduler: {} is due but {} runs are already active", sourceId, active.size());
        }
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
        var done = new CompletableFuture<Void>(); // lets shutdown wait for it
        active.put(sourceId, done);
        try {
            return coordinator.run(sourceId, properties.force());
        } finally {
            active.remove(sourceId);
            done.complete(null);
        }
    }

    @Override
    public void stop() {
        running = false;
        ScheduledFuture<?> current = ticking;
        if (current != null) {
            current.cancel(false);
        }
        coordinator.stopAll();
        long deadline = System.nanoTime() + properties.shutdownGrace().toNanos();
        for (var run : Map.copyOf(active).entrySet()) {
            try {
                run.getValue().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                // Interrupts a scheduled run's thread; the import's partitions are interrupted when their executor
                // closes.
                log.warn("ingest scheduler: {} still running after {}; cancelling", run.getKey(),
                        properties.shutdownGrace());
                run.getValue().cancel(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // the run's own failure; it is logged where it happened
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
