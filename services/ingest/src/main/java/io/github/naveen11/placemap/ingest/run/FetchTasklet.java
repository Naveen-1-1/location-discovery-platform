package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.ingest.IngestProperties;
import io.github.naveen11.placemap.ingest.catalog.SourceCatalog;
import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import io.github.naveen11.placemap.ingest.snapshot.Snapshot;
import io.github.naveen11.placemap.ingest.snapshot.SnapshotException;
import io.github.naveen11.placemap.ingest.snapshot.SnapshotScan;
import io.github.naveen11.placemap.ingest.snapshot.SnapshotStore;
import io.github.naveen11.placemap.ingest.source.SourceAdapter;
import io.github.naveen11.placemap.ingest.source.SourceAdapters;
import java.io.IOException;
import java.util.HexFormat;
import java.util.Optional;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/**
 * The job's first step: save the snapshot. If the publisher's version or the download is identical to the last
 * successful run's, and no place changed since, the run ends as UNCHANGED and the step exits with {@code NOOP}, which
 * ends the job. A run past this step (RUNNING) has its snapshot already; a resumed attempt finds nothing to do here.
 */
final class FetchTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(FetchTasklet.class);

    private final IngestProperties properties;
    private final SourceCatalog catalog;
    private final SourceAdapters adapters;
    private final SnapshotStore snapshots;
    private final RunStore runs;

    FetchTasklet(IngestProperties properties, SourceCatalog catalog, SourceAdapters adapters, SnapshotStore snapshots,
            RunStore runs) {
        this.properties = properties;
        this.catalog = catalog;
        this.adapters = adapters;
        this.snapshots = snapshots;
        this.runs = runs;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        JobParameters parameters = contribution.getStepExecution().getJobParameters();
        String sourceId = parameters.getString(ImportJobConfig.SOURCE);
        long runId = parameters.getLong(ImportJobConfig.RUN);
        boolean force = "true".equals(parameters.getString(ImportJobConfig.FORCE));
        RunRecord run = runs.find(runId);
        if (RunRecord.RUNNING.equals(run.status())) {
            return RepeatStatus.FINISHED; // an earlier attempt saved the snapshot
        }
        if (!RunRecord.FETCHING.equals(run.status())) {
            contribution.setExitStatus(ExitStatus.NOOP); // finished by an attempt that failed only afterwards
            return RepeatStatus.FINISHED;
        }
        SourceAdapter adapter = adapters.get(sourceId);
        SourceDefinition source = catalog.get(sourceId);
        Optional<RunRecord> last = runs.lastSuccessful(sourceId);
        // A changed place set means the records may now match differently, so it also forces a full run.
        boolean placesUnchanged = last.isPresent() && !runs.placesChangedSince(last.get().runId());
        // A pinned snapshot (crash tests, benchmarks) has no publisher to ask.
        String version = snapshots.pinned() ? null : sourceVersion(adapter, source, snapshots.http());
        if (!force && placesUnchanged && version != null && version.equals(last.get().sourceVersion())) {
            runs.markUnchanged(runId, last.get().snapshotSha256(), version);
            snapshots.keepOnly(sourceId, runId);
            log.info("ingest {}: run {} unchanged since the last import (publisher version {}); nothing downloaded",
                    sourceId, runId, version);
            contribution.setExitStatus(ExitStatus.NOOP);
            return RepeatStatus.FINISHED;
        }
        Snapshot snapshot = snapshots.fetch(adapter, source, runId);
        if (!force && placesUnchanged && snapshot.sha256().equals(last.get().snapshotSha256())) {
            runs.markUnchanged(runId, snapshot.sha256(), version);
            snapshots.keepOnly(sourceId, runId);
            log.info("ingest {}: run {} unchanged since the last import", sourceId, runId);
            contribution.setExitStatus(ExitStatus.NOOP);
            return RepeatStatus.FINISHED;
        }
        SnapshotScan scan = SnapshotScan.of(snapshot.file(), adapter);
        if (scan.rows() == 0) {
            throw new SnapshotException("the download has no rows; refusing to retire every record");
        }
        runs.markRunning(runId, snapshot.sha256(), version, scan.rows(), scan.batches(properties.batchSize()));
        return RepeatStatus.FINISHED;
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
            return remote + "|" + HexFormat.of().formatHex(DigestUtils.sha256(request), 0, 6);
        } catch (IOException | RuntimeException e) {
            log.warn("ingest {}: could not read the publisher's version ({}); downloading to compare", source.id(),
                    e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
