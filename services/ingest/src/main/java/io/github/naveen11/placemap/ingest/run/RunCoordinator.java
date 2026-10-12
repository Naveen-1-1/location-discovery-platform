package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.concurrent.BoundedChannel;
import io.github.naveen11.placemap.concurrent.ContiguousWatermark;
import io.github.naveen11.placemap.ingest.IngestProperties;
import io.github.naveen11.placemap.ingest.catalog.CategoryRules;
import io.github.naveen11.placemap.ingest.catalog.CoverageArea;
import io.github.naveen11.placemap.ingest.catalog.SourceCatalog;
import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.catalog.SourcePriority;
import io.github.naveen11.placemap.ingest.geocode.Geocoding;
import io.github.naveen11.placemap.ingest.match.Matcher;
import io.github.naveen11.placemap.ingest.match.PlaceIndex;
import io.github.naveen11.placemap.ingest.pipeline.Pipeline;
import io.github.naveen11.placemap.ingest.pipeline.PlatformThreads;
import io.github.naveen11.placemap.ingest.pipeline.StageFailedException;
import io.github.naveen11.placemap.ingest.pipeline.StageListener;
import io.github.naveen11.placemap.ingest.snapshot.BatchReader;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import io.github.naveen11.placemap.ingest.snapshot.RawBatch;
import io.github.naveen11.placemap.ingest.snapshot.Snapshot;
import io.github.naveen11.placemap.ingest.snapshot.SnapshotException;
import io.github.naveen11.placemap.ingest.snapshot.SnapshotScan;
import io.github.naveen11.placemap.ingest.snapshot.SnapshotStore;
import io.github.naveen11.placemap.ingest.source.ParseContext;
import io.github.naveen11.placemap.ingest.source.SourceAdapter;
import io.github.naveen11.placemap.ingest.source.SourceAdapters;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one import of one source from start to finish, or resumes it:
 *
 * <ol>
 *   <li>Take the source's advisory lock, or skip if another process holds it.
 *   <li>Open a new run, or resume the source's unfinished one.
 *   <li>Fetch: save the snapshot. If it is identical to the last successful one, finish as UNCHANGED.
 *   <li>Scan it (row count, repeated keys) and load the frozen place index, then run read → parse → match → upsert,
 *       skipping batches already committed.
 *   <li>Once every batch is committed, complete the run in one transaction (retire vanished records, refresh the
 *       places they belong to).
 * </ol>
 *
 * Errors leave the run open for the next tick, up to {@code max-attempts}; a bad snapshot or a safety limit fails it.
 */
public class RunCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RunCoordinator.class);

    public enum Outcome {
        /** All batches committed and the run completed. */
        COMPLETE,
        /** The download and the places both match the last successful run; nothing to do. */
        UNCHANGED,
        /** The run was closed as failed (bad snapshot, safety limit, or too many attempts). */
        FAILED,
        /** Shutdown stopped the run; it stays open and resumes on the next start. */
        STOPPED,
        /** An error left the run open; a later tick retries it. */
        ERROR,
        /** Another process is running this source. */
        LOCKED
    }

    private final IngestProperties properties;
    private final SourceCatalog catalog;
    private final SourceAdapters adapters;
    private final CategoryRules categories;
    private final SourcePriority priority;
    private final CoverageArea area;
    private final SnapshotStore snapshots;
    private final RunStore runs;
    private final DataSource dataSource;
    private final IngestMetrics metrics;
    private final Geocoding geocoding;

    public RunCoordinator(IngestProperties properties, SourceCatalog catalog, SourceAdapters adapters,
            CategoryRules categories, SourcePriority priority, CoverageArea area, SnapshotStore snapshots,
            RunStore runs, DataSource dataSource, IngestMetrics metrics, Geocoding geocoding) {
        this.properties = properties;
        this.catalog = catalog;
        this.adapters = adapters;
        this.categories = categories;
        this.priority = priority;
        this.area = area;
        this.snapshots = snapshots;
        this.runs = runs;
        this.dataSource = dataSource;
        this.metrics = metrics;
        this.geocoding = geocoding;
    }

    public Outcome run(String sourceId, RunControl control, boolean force) {
        control.attach(Thread.currentThread());
        Outcome outcome;
        try {
            outcome = lockedRun(sourceId, control, force);
        } catch (Exception e) {
            // Could not even open the run (database unreachable, lock query failed): try again next tick.
            log.error("ingest {}: could not start the run", sourceId, e);
            outcome = Outcome.ERROR;
        }
        metrics.runFinished(sourceId, outcome);
        return outcome;
    }

    private Outcome lockedRun(String sourceId, RunControl control, boolean force) throws Exception {
        SourceAdapter adapter = adapters.get(sourceId);
        SourceDefinition source = catalog.get(sourceId);
        Optional<SourceLock> lock = SourceLock.tryAcquire(dataSource, sourceId);
        if (lock.isEmpty()) {
            log.info("ingest {}: another process is running this source; skipping", sourceId);
            return Outcome.LOCKED;
        }
        try (SourceLock held = lock.get()) {
            RunRecord run = runs.findOpen(sourceId).map(runs::resume).orElseGet(() -> runs.create(sourceId));
            log.info("ingest {}: run {} {} (attempt {})", sourceId, run.runId(),
                    run.attempts() > 1 ? "resumed" : "started", run.attempts());
            try {
                return execute(run, adapter, source, held, control, force);
            } catch (InterruptedException e) {
                log.info("ingest {}: run {} interrupted; it resumes on the next start", sourceId, run.runId());
                return Outcome.STOPPED;
            } catch (SnapshotException e) {
                log.error("ingest {}: run {} failed: {}", sourceId, run.runId(), e.getMessage());
                runs.markFailed(run.runId(), e.getMessage());
                return Outcome.FAILED;
            } catch (Exception e) {
                if (control.stopRequested()) {
                    log.info("ingest {}: run {} stopped during shutdown ({}); it resumes on the next start",
                            sourceId, run.runId(), e.toString());
                    return Outcome.STOPPED;
                }
                if (run.attempts() >= properties.maxAttempts()) {
                    log.error("ingest {}: run {} failed after {} attempts", sourceId, run.runId(), run.attempts(), e);
                    runs.markFailed(run.runId(), e.toString());
                    return Outcome.FAILED;
                }
                log.warn("ingest {}: run {} hit an error; will retry", sourceId, run.runId(), e);
                runs.recordError(run.runId(), e.toString());
                return Outcome.ERROR;
            }
        }
    }

    private Outcome execute(RunRecord run, SourceAdapter adapter, SourceDefinition source, SourceLock lock,
            RunControl control, boolean force) throws Exception {
        String sourceId = source.id();
        int batchSize = properties.batchSize();
        Snapshot snapshot;
        SnapshotScan scan;
        if (RunRecord.FETCHING.equals(run.status())) {
            if (control.stopRequested()) {
                return Outcome.STOPPED;
            }
            Optional<RunRecord> last = runs.lastSuccessful(sourceId);
            // A changed place set means the records may now match differently, so it also forces a full run.
            boolean placesUnchanged = last.isPresent() && !runs.placesChangedSince(last.get().runId());
            // A pinned snapshot (crash tests, benchmarks) has no publisher to ask.
            String version = snapshots.pinned() ? null : sourceVersion(adapter, source, snapshots.http());
            if (!force && placesUnchanged && version != null && version.equals(last.get().sourceVersion())) {
                runs.markUnchanged(run.runId(), last.get().snapshotSha256(), version);
                snapshots.keepOnly(sourceId, run.runId());
                log.info("ingest {}: run {} unchanged since the last import (publisher version {}); nothing downloaded",
                        sourceId, run.runId(), version);
                return Outcome.UNCHANGED;
            }
            snapshot = snapshots.fetch(adapter, source, run.runId());
            if (!force && placesUnchanged && snapshot.sha256().equals(last.get().snapshotSha256())) {
                runs.markUnchanged(run.runId(), snapshot.sha256(), version);
                snapshots.keepOnly(sourceId, run.runId());
                log.info("ingest {}: run {} unchanged since the last import", sourceId, run.runId());
                return Outcome.UNCHANGED;
            }
            scan = SnapshotScan.of(snapshot.file(), adapter);
            if (scan.rows() == 0) {
                throw new SnapshotException("the download has no rows; refusing to retire every record");
            }
            runs.markRunning(run.runId(), snapshot.sha256(), version, scan.rows(), scan.batches(batchSize));
            run = run.running(snapshot.sha256(), version, scan.rows(), scan.batches(batchSize));
        } else {
            String expected = run.snapshotSha256();
            long runId = run.runId();
            snapshot = snapshots.existing(sourceId, runId)
                    .filter(s -> s.sha256().equals(expected))
                    .orElseThrow(() -> new SnapshotException(
                            "the saved snapshot of run " + runId + " is missing or changed"));
            scan = SnapshotScan.of(snapshot.file(), adapter);
            if (scan.batches(batchSize) != run.totalBatches()) {
                throw new SnapshotException("batch size changed since run " + run.runId() + " started");
            }
        }

        int totalBatches = run.totalBatches();
        Set<Integer> done = runs.doneBatches(run.runId());
        var watermark = new ContiguousWatermark();
        done.forEach(watermark::markDone);
        if (done.size() < totalBatches) {
            log.info("ingest {}: run {} has {} rows in {} batches, {} already committed", sourceId, run.runId(),
                    scan.rows(), totalBatches, done.size());
            runPipeline(run, adapter, snapshot, scan, done, watermark, control);
        }
        if (watermark.current() != totalBatches - 1) {
            if (control.stopRequested()) {
                log.info("ingest {}: run {} stopped at {} of {} batches; it resumes on the next start", sourceId,
                        run.runId(), watermark.current() + 1, totalBatches);
                return Outcome.STOPPED;
            }
            throw new IllegalStateException("pipeline ended with " + (watermark.current() + 1) + " of " + totalBatches
                    + " batches committed");
        }

        RunStore.Completion completion = runs.complete(lock.connection(), run.runId(), sourceId,
                properties.maxRejectShare(), properties.maxRetireShare(), priority.roles());
        snapshots.keepOnly(sourceId, run.runId());
        if (!completion.complete()) {
            metrics.limitTripped(sourceId, completion.limit());
            log.error("ingest {}: run {} failed: {}", sourceId, run.runId(), completion.error());
            return Outcome.FAILED;
        }
        log.info("ingest {}: run {} complete: {} rows, {} rejected, {} retired, checksum {}", sourceId, run.runId(),
                scan.rows(), completion.rejected(), completion.retired(), completion.checksum());
        return Outcome.COMPLETE;
    }

    /**
     * The publisher's version marker plus a fingerprint of what this importer asks for (its class and columns), so a
     * code change that requests different data never counts as "unchanged". Null when the source has no marker or it
     * could not be read; the run then downloads and compares the file instead.
     */
    private static String sourceVersion(SourceAdapter adapter, SourceDefinition source, HttpDownloader http) {
        try {
            String remote = adapter.remoteVersion(source, http);
            if (remote == null) {
                return null;
            }
            String request = adapter.getClass().getName() + ":" + String.join(",", adapter.columns());
            return remote + "|" + HexFormat.of().formatHex(sha256(request), 0, 6);
        } catch (IOException | RuntimeException e) {
            log.warn("ingest {}: could not read the publisher's version ({}); downloading to compare", source.id(),
                    e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK ships SHA-256", e);
        }
    }

    private void runPipeline(RunRecord run, SourceAdapter adapter, Snapshot snapshot, SnapshotScan scan,
            Set<Integer> done, ContiguousWatermark watermark, RunControl control) throws Exception {
        String sourceId = adapter.sourceId();
        int parseWorkers = properties.parseWorkerCount();
        int matchWorkers = properties.matchWorkerCount();
        int upsertWorkers = properties.upsertWorkers();
        // H3 is native code, so the index is built on a platform thread (as are the match workers that query it).
        PlaceIndex index = PlatformThreads.call("ingest-index-" + sourceId,
                () -> PlaceIndex.load(dataSource, sourceId, run.runId(), adapter.joinsSource().orElse(null)));
        var matcher = new Matcher(index, priority, sourceId);
        log.info("ingest {}: matching against {} places", sourceId, index.size());
        // Sources whose records can lack a location get a geocode stage between parse and match, sized like parse.
        boolean geocodes = adapter.geocodesMissingLocations();
        int geocodeWorkers = properties.parseWorkerCount();
        // Each queue holds twice its consumers' worker count, in batches of batch-size rows (the plan's sizing).
        var raw = new BoundedChannel<RawBatch>(2 * parseWorkers);
        var parsed = new BoundedChannel<ParsedBatch>(2 * (geocodes ? geocodeWorkers : matchWorkers));
        var located = geocodes ? new BoundedChannel<ParsedBatch>(2 * matchWorkers) : parsed;
        var matched = new BoundedChannel<MatchedBatch>(2 * upsertWorkers);
        var reader = new BatchReader(snapshot.file(), adapter.columns(), properties.batchSize(), scan, done);

        try (IngestMetrics.RunMetrics runMetrics = metrics.forRun(sourceId)) {
            runMetrics.watchQueue("parse", raw);
            if (geocodes) {
                runMetrics.watchQueue("geocode", parsed);
            }
            runMetrics.watchQueue("match", located);
            runMetrics.watchQueue("upsert", matched);
            runMetrics.watchProgress(watermark, run.totalBatches());
            StageListener readMetrics = runMetrics.stage("read");

            var pipeline = new Pipeline();
            // Reading the file is I/O-bound and pure Java, so a virtual thread suits it. Parsing and matching are CPU
            // work (matching also calls H3), and the upsert holds one JDBC connection per thread: all three get a
            // fixed number of platform threads.
            pipeline.source("read", Thread.ofVirtual().name("ingest-read-" + sourceId).factory(), raw,
                    (out, stopRequested) -> reader.readInto(out, stopRequested, readMetrics));
            pipeline.stage("parse", parseWorkers,
                    Thread.ofPlatform().name("ingest-parse-" + sourceId + "-", 1).factory(), raw, parsed,
                    () -> new ParseWorker(adapter, new ParseContext(categories, area.checker())),
                    runMetrics.stage("parse"));
            if (geocodes) {
                // Platform threads like their neighbours; each fans its batch's service calls out on virtual threads.
                pipeline.stage("geocode", geocodeWorkers,
                        Thread.ofPlatform().name("ingest-geocode-" + sourceId + "-", 1).factory(), parsed, located,
                        () -> new GeocodeWorker(sourceId, geocoding, area.checker(), runMetrics),
                        runMetrics.stage("geocode"));
            }
            pipeline.stage("match", matchWorkers,
                    Thread.ofPlatform().name("ingest-match-" + sourceId + "-", 1).factory(), located, matched,
                    () -> new MatchWorker(matcher), runMetrics.stage("match"));
            pipeline.sink("upsert", upsertWorkers,
                    Thread.ofPlatform().name("ingest-upsert-" + sourceId + "-", 1).factory(), matched,
                    () -> new BatchWriter(dataSource, run.runId(), sourceId, watermark, runMetrics),
                    runMetrics.stage("upsert"));

            control.attach(pipeline);
            pipeline.start();
            try {
                while (!pipeline.awaitTermination(Duration.ofSeconds(30))) {
                    log.debug("ingest {}: {} of {} batches committed", sourceId, watermark.current() + 1,
                            run.totalBatches());
                }
            } catch (InterruptedException e) {
                pipeline.cancel(e);
                throw e;
            }
            Optional<Throwable> failure = pipeline.failure();
            if (failure.isPresent()) {
                Throwable cause = failure.get();
                if (cause instanceof StageFailedException stage && stage.getCause() instanceof SnapshotException bad) {
                    throw bad; // retrying cannot fix the input
                }
                throw cause instanceof Exception exception ? exception : new IllegalStateException(cause);
            }
        }
    }
}
