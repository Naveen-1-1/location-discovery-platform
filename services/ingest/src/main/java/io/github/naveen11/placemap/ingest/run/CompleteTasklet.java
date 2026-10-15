package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.ingest.IngestProperties;
import io.github.naveen11.placemap.ingest.catalog.SourcePriority;
import io.github.naveen11.placemap.ingest.snapshot.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/**
 * The job's last step, once every batch is committed: complete the run in one transaction on the source lock's
 * connection ({@link RunStore#complete}), so it only commits while this process still holds the lock. A safety limit
 * that trips fails the run there instead; the step still succeeds, since the run's outcome is recorded.
 */
final class CompleteTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(CompleteTasklet.class);

    private final IngestProperties properties;
    private final SourcePriority priority;
    private final SnapshotStore snapshots;
    private final RunStore runs;
    private final ImportRuns active;
    private final IngestMetrics metrics;

    CompleteTasklet(IngestProperties properties, SourcePriority priority, SnapshotStore snapshots, RunStore runs,
            ImportRuns active, IngestMetrics metrics) {
        this.properties = properties;
        this.priority = priority;
        this.snapshots = snapshots;
        this.runs = runs;
        this.active = active;
        this.metrics = metrics;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        JobParameters parameters = contribution.getStepExecution().getJobParameters();
        String sourceId = parameters.getString(ImportJobConfig.SOURCE);
        long runId = parameters.getLong(ImportJobConfig.RUN);
        RunRecord run = runs.find(runId);
        if (!RunRecord.RUNNING.equals(run.status())) {
            return RepeatStatus.FINISHED; // completed (or failed) by an attempt whose acknowledgement was lost
        }
        int done = runs.doneBatches(runId).size();
        if (done != run.totalBatches()) {
            throw new IllegalStateException("import ended with " + done + " of " + run.totalBatches()
                    + " batches committed");
        }
        RunStore.Completion completion = runs.complete(active.lock(runId).connection(), runId, sourceId,
                properties.maxRejectShare(), properties.maxRetireShare(), priority.roles());
        snapshots.keepOnly(sourceId, runId);
        if (!completion.complete()) {
            metrics.limitTripped(sourceId, completion.limit());
            log.error("ingest {}: run {} failed: {}", sourceId, runId, completion.error());
        } else {
            log.info("ingest {}: run {} complete: {} rows, {} rejected, {} retired, checksum {}", sourceId, runId,
                    run.totalRows(), completion.rejected(), completion.retired(), completion.checksum());
        }
        return RepeatStatus.FINISHED;
    }
}
