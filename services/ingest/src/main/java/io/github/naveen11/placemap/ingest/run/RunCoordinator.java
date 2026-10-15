package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.ingest.IngestProperties;
import io.github.naveen11.placemap.ingest.snapshot.SnapshotException;
import java.io.Serial;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Runs one import of one source from start to finish, or resumes it, on the calling thread:
 *
 * <ol>
 *   <li>Take the source's advisory lock, or skip if another process holds it.
 *   <li>Open a new run, or resume the source's unfinished one.
 *   <li>Start the run's import job, or restart it where the last attempt stopped ({@link ImportJobConfig}).
 *   <li>Record the outcome. Errors leave the run open for the next tick, up to {@code max-attempts}; a bad snapshot or
 *       a safety limit fails it.
 * </ol>
 *
 * <p>An attempt killed with its process (or cut off from the database) leaves its job execution marked as running,
 * or with a status Spring Batch could not finish writing. Holding the source's lock proves no process still runs it,
 * so such an execution is recovered (marked failed) and restarted. That is safe because which batches are done is
 * read from {@code ingest.batch_done}, written with each batch, never from Spring Batch's own bookkeeping.
 *
 * <p>Spring Batch writes that bookkeeping at every step and batch, with no retry of its own, so a dropped connection
 * there fails the job. Such a job is relaunched within the attempt ({@link #RELAUNCH}), after taking the source's lock
 * again if the drop took it too; a restart redoes only what is not done.
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

    /**
     * Relaunches of the job within one attempt after a failure another try can fix (a deadlock, a dropped
     * connection): 1 s, doubling to at most 30 s, 8 times at most.
     */
    static final RetryPolicy RELAUNCH = RetryPolicy.builder()
            .predicate(RunCoordinator::isTransient)
            .maxRetries(8)
            .delay(Duration.ofSeconds(1))
            .multiplier(2)
            .maxDelay(Duration.ofSeconds(30))
            .build();

    /** The job failed on a transient error; its execution decides the outcome if the relaunches run out. */
    private static final class TransientJobFailure extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        private final transient JobExecution execution;

        TransientJobFailure(JobExecution execution) {
            super("the import job failed on a database error",
                    execution.getAllFailureExceptions().stream().findFirst().orElse(null));
            this.execution = execution;
        }
    }

    private final IngestProperties properties;
    private final RunStore runs;
    private final DataSource dataSource;
    private final IngestMetrics metrics;
    private final JobOperator jobOperator;
    private final JobRepository jobRepository;
    private final Job importJob;
    private final ImportRuns active;

    public RunCoordinator(IngestProperties properties, RunStore runs, DataSource dataSource, IngestMetrics metrics,
            JobOperator jobOperator, JobRepository jobRepository, Job importJob, ImportRuns active) {
        this.properties = properties;
        this.runs = runs;
        this.dataSource = dataSource;
        this.metrics = metrics;
        this.jobOperator = jobOperator;
        this.jobRepository = jobRepository;
        this.importJob = importJob;
        this.active = active;
    }

    public Outcome run(String sourceId, boolean force) {
        Outcome outcome;
        try {
            outcome = lockedRun(sourceId, force);
        } catch (Exception e) {
            // Could not even open the run (database unreachable, lock query failed): try again next tick.
            log.error("ingest {}: could not start the run", sourceId, e);
            outcome = Outcome.ERROR;
        }
        metrics.runFinished(sourceId, outcome);
        return outcome;
    }

    /** Asks every import running in this process to stop after the batches it is committing. */
    public void stopAll() {
        for (long executionId : active.runningExecutions()) {
            try {
                jobOperator.stop(jobRepository.getJobExecution(executionId));
            } catch (Exception e) {
                log.warn("ingest: could not stop job execution {} ({})", executionId, e.toString());
            }
        }
    }

    private Outcome lockedRun(String sourceId, boolean force) throws Exception {
        Optional<SourceLock> lock = SourceLock.tryAcquire(dataSource, sourceId);
        if (lock.isEmpty()) {
            log.info("ingest {}: another process is running this source; skipping", sourceId);
            return Outcome.LOCKED;
        }
        RunRecord run;
        try {
            run = runs.findOpen(sourceId).map(runs::resume).orElseGet(() -> runs.create(sourceId));
        } catch (RuntimeException e) {
            lock.get().close();
            throw e;
        }
        log.info("ingest {}: run {} {} (attempt {})", sourceId, run.runId(),
                run.attempts() > 1 ? "resumed" : "started", run.attempts());
        active.locked(run.runId(), lock.get());
        try {
            return execute(run, force);
        } finally {
            active.unlocked(run.runId()).close(); // the lock held now: the first, or a later one taken again
        }
    }

    private Outcome execute(RunRecord run, boolean force) {
        String sourceId = run.source();
        JobParameters parameters = new JobParametersBuilder()
                .addString(ImportJobConfig.SOURCE, sourceId)
                .addLong(ImportJobConfig.RUN, run.runId())
                .addString(ImportJobConfig.FORCE, Boolean.toString(force), false)
                .toJobParameters();
        var relaunches = new RetryTemplate(RELAUNCH);
        relaunches.setRetryListener(new RetryListener() {
            @Override
            public void beforeRetry(RetryPolicy policy, Retryable<?> retryable, RetryState state) {
                log.warn("ingest {}: run {}: the import job failed on a database error ({}); relaunching it ({} of 8)",
                        sourceId, run.runId(), state.getLastException().toString(), state.getRetryCount());
            }
        });
        JobExecution execution;
        try {
            execution = relaunches.invoke(() -> launch(run, parameters));
        } catch (TransientJobFailure e) {
            execution = e.execution;
        } catch (RuntimeException e) {
            return attemptFailed(run, List.of(e));
        }
        RunRecord after = runs.find(run.runId());
        return switch (after.status()) {
            case RunRecord.COMPLETE -> Outcome.COMPLETE;
            case RunRecord.UNCHANGED -> Outcome.UNCHANGED;
            case RunRecord.FAILED -> Outcome.FAILED; // a safety limit tripped at completion
            default -> {
                if (execution.getStatus() == BatchStatus.STOPPED) {
                    log.info("ingest {}: run {} stopped at {} of {} batches; it resumes on the next start", sourceId,
                            run.runId(), runs.doneBatches(run.runId()).size(), after.totalBatches());
                    yield Outcome.STOPPED;
                }
                yield attemptFailed(after, execution.getAllFailureExceptions());
            }
        };
    }

    /** Starts or restarts the run's job, on this thread; throws if it failed on an error another try can fix. */
    private JobExecution launch(RunRecord run, JobParameters parameters) {
        keepLocked(run);
        markDeadExecutionFailed(parameters);
        JobExecution execution;
        try {
            execution = jobOperator.start(importJob, parameters);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("could not launch the import job of run " + run.runId(), e);
        }
        if (execution.getStatus() == BatchStatus.FAILED
                && execution.getAllFailureExceptions().stream().anyMatch(RunCoordinator::isTransient)) {
            throw new TransientJobFailure(execution);
        }
        return execution;
    }

    /** Takes the source's lock again if its connection was lost, which releases it. */
    private void keepLocked(RunRecord run) {
        SourceLock lock = active.lock(run.runId());
        if (lock.isHeld()) {
            return;
        }
        lock.close();
        try {
            SourceLock again = SourceLock.tryAcquire(dataSource, run.source()).orElseThrow(() ->
                    new IllegalStateException("another process took the source's lock after this one lost it"));
            active.locked(run.runId(), again);
            log.info("ingest {}: run {}: took the source's lock again after losing its connection", run.source(),
                    run.runId());
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException("could not take the source's lock again", e);
        }
    }

    private static boolean isTransient(Throwable failure) {
        return failure instanceof TransientJobFailure || DatabaseErrors.isTransient(failure);
    }

    /** The attempt ended in errors with the run still open: fail the run, or leave it for a later tick. */
    private Outcome attemptFailed(RunRecord run, List<Throwable> failures) {
        Throwable failure = failures.isEmpty() ? new IllegalStateException("the import job ended without completing")
                : failures.getFirst();
        Optional<SnapshotException> bad = failures.stream()
                .map(f -> ExceptionUtils.throwableOfType(f, SnapshotException.class))
                .filter(Objects::nonNull)
                .findFirst();
        if (bad.isPresent()) { // retrying cannot fix the input
            log.error("ingest {}: run {} failed: {}", run.source(), run.runId(), bad.get().getMessage());
            runs.markFailed(run.runId(), bad.get().getMessage());
            return Outcome.FAILED;
        }
        if (run.attempts() >= properties.maxAttempts()) {
            log.error("ingest {}: run {} failed after {} attempts", run.source(), run.runId(), run.attempts(), failure);
            runs.markFailed(run.runId(), failure.toString());
            return Outcome.FAILED;
        }
        log.warn("ingest {}: run {} hit an error; will retry", run.source(), run.runId(), failure);
        runs.recordError(run.runId(), failure.toString());
        return Outcome.ERROR;
    }

    /**
     * An execution of this run, or a step of it, still marked running belongs to an attempt that died, or failed
     * before it could say so (this process holds the source's lock, so no other process runs it): Spring Batch's
     * recovery marks them failed, so the job can restart. An execution or step left UNKNOWN (its final status could
     * not be written, as when its connection was cut) is marked failed too; Spring Batch leaves that to someone who
     * knows a redo is safe, as it is here.
     */
    private void markDeadExecutionFailed(JobParameters parameters) {
        JobExecution last = jobRepository.getLastJobExecution(ImportJobConfig.JOB, parameters);
        if (last == null) {
            return;
        }
        if (last.getStatus() == BatchStatus.UNKNOWN) {
            last.setStatus(BatchStatus.FAILED);
            jobRepository.update(last);
        }
        // A step can be left running in an execution marked failed: its own last update was the one that failed.
        if (last.getStatus().isRunning() || last.getStepExecutions().stream().anyMatch(s -> s.getStatus().isRunning())) {
            log.info("ingest {}: job execution {} of run {} died with its attempt; recovering it",
                    parameters.getString(ImportJobConfig.SOURCE), last.getId(),
                    parameters.getLong(ImportJobConfig.RUN));
            jobOperator.recover(last);
        }
        for (StepExecution step : last.getStepExecutions()) {
            if (step.getStatus() == BatchStatus.UNKNOWN) {
                step.setStatus(BatchStatus.FAILED);
                jobRepository.update(step);
            }
        }
    }
}
