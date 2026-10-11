package io.github.naveen11.placemap.ingest.run;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Reads and writes {@code ingest.run} and runs the completion transaction. */
public class RunStore {

    private static final String RUN_COLUMNS = """
            run_id, source, status, snapshot_sha256, total_rows, total_batches, attempts, started_at, finished_at
            """;

    /** What completing a run did. {@code error} is set when a safety limit failed the run instead. */
    public record Completion(boolean complete, int rejected, int retired, String checksum, String error) {
    }

    private final DataSource dataSource;
    private final JdbcClient jdbc;

    public RunStore(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
    }

    public Optional<RunRecord> findOpen(String source) {
        return jdbc.sql("SELECT " + RUN_COLUMNS + " FROM ingest.run WHERE source = ? AND status IN ('FETCHING', 'RUNNING')")
                .param(source)
                .query(RunRecord.class)
                .optional();
    }

    public RunRecord create(String source) {
        return jdbc.sql("INSERT INTO ingest.run (source) VALUES (?) RETURNING " + RUN_COLUMNS)
                .param(source)
                .query(RunRecord.class)
                .single();
    }

    public RunRecord resume(RunRecord run) {
        return jdbc.sql("UPDATE ingest.run SET attempts = attempts + 1 WHERE run_id = ? RETURNING " + RUN_COLUMNS)
                .param(run.runId())
                .query(RunRecord.class)
                .single();
    }

    public Optional<RunRecord> latestFinished(String source) {
        return jdbc.sql("""
                        SELECT %s FROM ingest.run
                        WHERE source = ? AND status IN ('COMPLETE', 'UNCHANGED', 'FAILED')
                        ORDER BY run_id DESC LIMIT 1
                        """.formatted(RUN_COLUMNS))
                .param(source)
                .query(RunRecord.class)
                .optional();
    }

    /** The snapshot hash of the latest successful run, to skip unchanged downloads. */
    public Optional<String> lastSuccessfulSnapshot(String source) {
        return jdbc.sql("""
                        SELECT snapshot_sha256 FROM ingest.run
                        WHERE source = ? AND status IN ('COMPLETE', 'UNCHANGED')
                        ORDER BY run_id DESC LIMIT 1
                        """)
                .param(source)
                .query(String.class)
                .optional();
    }

    public void markRunning(long runId, String sha256, int rows, int batches) {
        jdbc.sql("""
                        UPDATE ingest.run SET status = 'RUNNING', snapshot_sha256 = ?, total_rows = ?, total_batches = ?
                        WHERE run_id = ?
                        """)
                .params(sha256, rows, batches, runId)
                .update();
    }

    public void markUnchanged(long runId, String sha256) {
        jdbc.sql("""
                        UPDATE ingest.run SET status = 'UNCHANGED', snapshot_sha256 = ?, error = NULL, finished_at = now()
                        WHERE run_id = ?
                        """)
                .params(sha256, runId)
                .update();
    }

    public void markFailed(long runId, String error) {
        jdbc.sql("UPDATE ingest.run SET status = 'FAILED', error = ?, finished_at = now() WHERE run_id = ?")
                .params(error, runId)
                .update();
    }

    /** Notes an error on a run that stays open, to be resumed on a later tick. */
    public void recordError(long runId, String error) {
        jdbc.sql("UPDATE ingest.run SET error = ? WHERE run_id = ?").params(error, runId).update();
    }

    public Set<Integer> doneBatches(long runId) {
        return new HashSet<>(jdbc.sql("SELECT seq FROM ingest.batch_done WHERE run_id = ?")
                .param(runId)
                .query(Integer.class)
                .list());
    }

    /**
     * Finishes a run whose batches are all committed, in one transaction (ADR 0020): checks the reject limit,
     * retires the source's records that this run did not see (never deletes them), and marks the run COMPLETE with a
     * checksum of the source's live records. Two safety limits fail the run instead of finishing it: too many
     * rejected rows, or a snapshot that would retire too large a share of the live records (a truncated download
     * looks exactly like that).
     */
    public Completion complete(long runId, String source, double maxRejectShare, double maxRetireShare)
            throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                var tx = JdbcClient.create(new SingleConnectionDataSource(c, true));
                // Skipped rows (out of scope by design, e.g. Overture places in Newton) are not part of the share.
                var totals = tx.sql("""
                                SELECT coalesce(sum(rows - skipped), 0) AS rows, coalesce(sum(rejected), 0) AS rejected
                                FROM ingest.batch_done WHERE run_id = ?
                                """)
                        .param(runId)
                        .query((rs, i) -> new int[] {rs.getInt("rows"), rs.getInt("rejected")})
                        .single();
                int rows = totals[0];
                int rejected = totals[1];
                if (rejected > maxRejectShare * rows) {
                    String error = "%d of %d rows rejected, above the %.0f%% limit; see ingest.reject"
                            .formatted(rejected, rows, maxRejectShare * 100);
                    failInTransaction(tx, runId, rejected, error);
                    c.commit();
                    return new Completion(false, rejected, 0, null, error);
                }

                tx.sql("CREATE TEMP TABLE seen_key (source_key text PRIMARY KEY) ON COMMIT DROP").update();
                tx.sql("""
                                INSERT INTO seen_key
                                SELECT DISTINCT k FROM ingest.batch_done, unnest(keys) AS k WHERE run_id = ?
                                """)
                        .param(runId)
                        .update();
                int live = tx.sql("SELECT count(*) FROM place.source_record WHERE source = ? AND retired_at IS NULL")
                        .param(source)
                        .query(Integer.class)
                        .single();
                int missing = tx.sql("""
                                SELECT count(*) FROM place.source_record r
                                WHERE r.source = ? AND r.retired_at IS NULL
                                  AND NOT EXISTS (SELECT 1 FROM seen_key s WHERE s.source_key = r.source_key)
                                """)
                        .param(source)
                        .query(Integer.class)
                        .single();
                if (missing > maxRetireShare * live) {
                    String error = "would retire %d of %d live records, above the %.0f%% limit; check the source"
                            .formatted(missing, live, maxRetireShare * 100);
                    failInTransaction(tx, runId, rejected, error);
                    c.commit();
                    return new Completion(false, rejected, 0, null, error);
                }
                int retired = tx.sql("""
                                UPDATE place.source_record r SET retired_at = now()
                                WHERE r.source = ? AND r.retired_at IS NULL
                                  AND NOT EXISTS (SELECT 1 FROM seen_key s WHERE s.source_key = r.source_key)
                                """)
                        .param(source)
                        .update();
                tx.sql("UPDATE ingest.batch_done SET keys = NULL WHERE run_id = ?").param(runId).update();
                String checksum = checksum(tx, source);
                tx.sql("""
                                UPDATE ingest.run
                                SET status = 'COMPLETE', rejected_rows = ?, result_checksum = ?, error = NULL,
                                    finished_at = now()
                                WHERE run_id = ?
                                """)
                        .params(rejected, checksum, runId)
                        .update();
                c.commit();
                return new Completion(true, rejected, retired, checksum, null);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /**
     * md5 over the source's live records in key order (byte order, so every database sorts alike). Equal checksums
     * mean equal data: the idempotency and crash tests compare runs with it.
     */
    public String checksum(String source) {
        return checksum(jdbc, source);
    }

    private static String checksum(JdbcClient client, String source) {
        return client.sql("""
                        SELECT md5(coalesce(string_agg(
                                   ROW(source_key, name, category, ST_AsEWKT(geom), address, postcode, phone, website,
                                       attrs, row_hash)::text,
                                   E'\\n' ORDER BY source_key COLLATE "C"), ''))
                        FROM place.source_record
                        WHERE source = ? AND retired_at IS NULL
                        """)
                .param(source)
                .query(String.class)
                .single();
    }

    private static void failInTransaction(JdbcClient tx, long runId, int rejected, String error) {
        tx.sql("UPDATE ingest.run SET status = 'FAILED', rejected_rows = ?, error = ?, finished_at = now() WHERE run_id = ?")
                .params(rejected, error, runId)
                .update();
    }
}
