package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.ingest.match.Matcher;
import io.github.naveen11.placemap.ingest.snapshot.Snapshot;
import io.github.naveen11.placemap.ingest.snapshot.SnapshotScan;
import io.github.naveen11.placemap.ingest.source.SourceAdapter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;

/**
 * What the steps of the imports running in this process share, by run id. Spring Batch hands steps only their
 * parameters, and its job scope does not reach partition threads, so the few objects a run's steps share live here
 * for as long as the run does:
 *
 * <ul>
 *   <li>the source lock, held by the thread that started the job (or taken again after its connection was lost); the
 *       completion step commits on its connection;
 *   <li>the import step's inputs (the frozen snapshot, its scan and the matcher), loaded once before the partitions
 *       start;
 *   <li>the job execution, so that shutdown can stop it.
 * </ul>
 */
final class ImportRuns implements JobExecutionListener {

    /** What every partition of a run reads. */
    record Inputs(SourceAdapter adapter, Snapshot snapshot, SnapshotScan scan, Matcher matcher) {
    }

    private final Map<Long, SourceLock> locks = new ConcurrentHashMap<>();
    private final Map<Long, Inputs> inputs = new ConcurrentHashMap<>();
    private final Map<Long, Long> executions = new ConcurrentHashMap<>();

    void locked(long runId, SourceLock lock) {
        locks.put(runId, lock);
    }

    /** @return the lock the run held last */
    SourceLock unlocked(long runId) {
        return locks.remove(runId);
    }

    SourceLock lock(long runId) {
        return require(locks, runId, "lock");
    }

    void loaded(long runId, Inputs loaded) {
        inputs.put(runId, loaded);
    }

    void unloaded(long runId) {
        inputs.remove(runId);
    }

    Inputs inputs(long runId) {
        return require(inputs, runId, "inputs");
    }

    /** The job executions running in this process. */
    List<Long> runningExecutions() {
        return List.copyOf(executions.values());
    }

    @Override
    public void beforeJob(JobExecution execution) {
        executions.put(runId(execution), execution.getId());
    }

    @Override
    public void afterJob(JobExecution execution) {
        executions.remove(runId(execution));
    }

    private static long runId(JobExecution execution) {
        return execution.getJobParameters().getLong(ImportJobConfig.RUN);
    }

    private static <T> T require(Map<Long, T> map, long runId, String what) {
        T value = map.get(runId);
        if (value == null) {
            throw new IllegalStateException("run " + runId + " has no " + what + " in this process");
        }
        return value;
    }
}
