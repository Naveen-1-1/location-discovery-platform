package io.github.naveen11.placemap.ingest.run;

import java.time.OffsetDateTime;

/** A row of {@code ingest.run}. */
public record RunRecord(
        long runId,
        String source,
        String status,
        String snapshotSha256,
        String sourceVersion,
        Integer totalRows,
        Integer totalBatches,
        int attempts,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {

    public static final String FETCHING = "FETCHING";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETE = "COMPLETE";
    public static final String UNCHANGED = "UNCHANGED";
    public static final String FAILED = "FAILED";

    RunRecord running(String sha256, String version, int rows, int batches) {
        return new RunRecord(runId, source, RUNNING, sha256, version, rows, batches, attempts, startedAt, finishedAt);
    }
}
