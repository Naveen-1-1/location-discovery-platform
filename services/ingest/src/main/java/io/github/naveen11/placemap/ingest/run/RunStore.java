package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.ingest.catalog.SourcePriority;
import io.github.naveen11.placemap.ingest.facts.PlaceFacts;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Reads and writes {@code ingest.run} and runs the completion transaction. */
public class RunStore {

    private static final String RUN_COLUMNS = """
            run_id, source, status, snapshot_sha256, source_version, total_rows, total_batches, attempts, started_at,
            finished_at
            """;

    /**
     * What completing a run did. When a safety limit failed the run instead, {@code limit} names it ("rejects" or
     * "retirements") and {@code error} explains.
     */
    public record Completion(boolean complete, int rejected, int retired, String checksum, String limit, String error) {
    }

    private final JdbcClient jdbc;

    public RunStore(DataSource dataSource) {
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

    /** The latest successful run of the source, to skip unchanged downloads. */
    public Optional<RunRecord> lastSuccessful(String source) {
        return jdbc.sql("""
                        SELECT %s FROM ingest.run
                        WHERE source = ? AND status IN ('COMPLETE', 'UNCHANGED')
                        ORDER BY run_id DESC LIMIT 1
                        """.formatted(RUN_COLUMNS))
                .param(source)
                .query(RunRecord.class)
                .optional();
    }

    /**
     * Whether any place was created or changed since the given run started. An unchanged download is only skipped
     * if the places are unchanged too; otherwise its records might now match places that did not exist before.
     */
    public boolean placesChangedSince(long runId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM place.place
                                       WHERE updated_at > (SELECT started_at FROM ingest.run WHERE run_id = ?))
                        """)
                .param(runId)
                .query(Boolean.class)
                .single();
    }

    public void markRunning(long runId, String sha256, String sourceVersion, int rows, int batches) {
        jdbc.sql("""
                        UPDATE ingest.run
                        SET status = 'RUNNING', snapshot_sha256 = ?, source_version = ?, total_rows = ?, total_batches = ?
                        WHERE run_id = ?
                        """)
                .params(sha256, sourceVersion, rows, batches, runId)
                .update();
    }

    public void markUnchanged(long runId, String sha256, String sourceVersion) {
        jdbc.sql("""
                        UPDATE ingest.run
                        SET status = 'UNCHANGED', snapshot_sha256 = ?, source_version = ?, error = NULL, finished_at = now()
                        WHERE run_id = ?
                        """)
                .params(sha256, sourceVersion, runId)
                .update();
    }

    public void markFailed(long runId, String error) {
        jdbc.sql("""
                        UPDATE ingest.run SET status = 'FAILED', error = ?, finished_at = now()
                        WHERE run_id = ? AND status IN ('FETCHING', 'RUNNING')
                        """)
                .params(error, runId)
                .update();
    }

    /**
     * Notes an error on a run that stays open, to be resumed on a later tick. Like {@link #markFailed}, it leaves a
     * finished run alone: an error can arrive after a commit that went through but whose acknowledgement was lost.
     */
    public void recordError(long runId, String error) {
        jdbc.sql("UPDATE ingest.run SET error = ? WHERE run_id = ? AND status IN ('FETCHING', 'RUNNING')")
                .params(error, runId)
                .update();
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
     *
     * @param c the connection holding the source's lock ({@link SourceLock#connection()}). If it drops, Postgres
     *     releases the lock and rolls this transaction back together, so a run only completes while its process still
     *     holds the lock. The connection stays open, in auto-commit mode, afterwards.
     */
    public Completion complete(Connection c, long runId, String source, double maxRejectShare, double maxRetireShare,
            List<SourcePriority.Role> roles) throws SQLException {
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
                return new Completion(false, rejected, 0, null, "rejects", error);
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
                return new Completion(false, rejected, 0, null, "retirements", error);
            }
            int retired = tx.sql("""
                            UPDATE place.source_record r SET retired_at = now()
                            WHERE r.source = ? AND r.retired_at IS NULL
                              AND NOT EXISTS (SELECT 1 FROM seen_key s WHERE s.source_key = r.source_key)
                            """)
                    .param(source)
                    .update();
            tx.sql("UPDATE ingest.batch_done SET keys = NULL WHERE run_id = ?").param(runId).update();
            // Earlier runs' rejects and checkpoints have served their purpose. Without this the reject table
            // would grow every day by the same rows (Boston's history alone rejects a few thousand) on a 1 GB
            // database. The run rows themselves stay as the import log.
            tx.sql("""
                    DELETE FROM ingest.reject
                    WHERE run_id IN (SELECT run_id FROM ingest.run WHERE source = ? AND run_id < ?)
                    """).params(source, runId).update();
            tx.sql("""
                    DELETE FROM ingest.batch_done
                    WHERE run_id IN (SELECT run_id FROM ingest.run WHERE source = ? AND run_id < ?)
                    """).params(source, runId).update();
            refreshPlaces(tx, source, roles);
            refreshInspectionSummary(tx, source);
            PlaceFacts.refresh(c, source);
            // Reviews this run did not repeat are stale: the record is linked now, or no longer borderline.
            tx.sql("DELETE FROM place.match_review WHERE source = ? AND run_id <> ?").params(source, runId).update();
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
            return new Completion(true, rejected, retired, checksum, null, null);
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            restoreAutoCommit(c);
        }
    }

    /**
     * md5 over the source's live records in key order (byte order, so every database sorts alike). Equal checksums
     * mean equal data: the idempotency and crash tests compare runs with it.
     */
    public String checksum(String source) {
        return checksum(jdbc, source);
    }

    /**
     * Places are linked by place_id, whose numbers depend on commit order, so the checksum names each record's place
     * by an anchor instead: the smallest "source:key" among the records linked to it. Two runs that group the
     * records into places the same way give the same checksum.
     */
    private static String checksum(JdbcClient client, String source) {
        return client.sql("""
                        WITH anchor AS (
                            SELECT l.source_key, min((o.source || ':' || o.source_key) COLLATE "C") AS anchor
                            FROM place.place_source_link l
                            JOIN place.place_source_link o ON o.place_id = l.place_id
                            WHERE l.source = ?
                            GROUP BY l.source_key)
                        SELECT md5(coalesce(string_agg(
                                   ROW(r.source_key, r.name, r.category, ST_AsEWKT(r.geom), r.address, r.postcode,
                                       r.phone, r.website, r.attrs, r.row_hash, a.anchor)::text,
                                   E'\\n' ORDER BY r.source_key COLLATE "C"), ''))
                        FROM place.source_record r
                        LEFT JOIN anchor a ON a.source_key = r.source_key
                        WHERE r.source = ? AND r.retired_at IS NULL
                        """)
                .params(source, source)
                .query(String.class)
                .single();
    }

    /**
     * Rebuilds the source's per-establishment inspection summary that the map's filters read (ADR 0034): latest
     * result and date, number of inspections, and violation lines marked Fail in the last 12 months. A no-op for
     * sources without inspections. Readers keep seeing the old rows until the run's transaction commits.
     */
    private static void refreshInspectionSummary(JdbcClient tx, String source) {
        tx.sql("DELETE FROM place.inspection_summary WHERE source = ?").param(source).update();
        tx.sql("""
                INSERT INTO place.inspection_summary
                    (source, source_key, latest_result, latest_at, inspections, failed_checks_12m)
                SELECT i.source, i.source_key,
                       (array_agg(i.result ORDER BY i.inspected_at DESC, i.result))[1],
                       max(i.inspected_at),
                       count(DISTINCT i.inspection_id),
                       count(v.line_hash) FILTER (WHERE v.status = 'Fail')
                FROM place.inspection i
                LEFT JOIN place.inspection_violation v
                       ON v.inspection_id = i.inspection_id AND i.inspected_at > now() - interval '12 months'
                WHERE i.source = ?
                GROUP BY i.source, i.source_key
                """).param(source).update();
        // Failed checks of the latest inspection (Cambridge's way of saying whether it passed; ADR 0042).
        tx.sql("""
                UPDATE place.inspection_summary s
                SET latest_failed_checks = latest.failed
                FROM (SELECT i.source_key, count(v.line_hash) FILTER (WHERE v.status = 'Fail') AS failed
                      FROM place.inspection i
                      JOIN place.inspection_summary s2 ON s2.source = i.source AND s2.source_key = i.source_key
                                                      AND s2.latest_at = i.inspected_at
                      LEFT JOIN place.inspection_violation v ON v.inspection_id = i.inspection_id
                      WHERE i.source = ?
                      GROUP BY i.source_key) latest
                WHERE s.source = ? AND s.source_key = latest.source_key
                """).params(source, source).update();
    }

    /**
     * Brings every place linked to this source up to date with its live records (ADR 0022, 0032):
     *
     * <ul>
     *   <li>Each attribute comes from the best-ranked live record that has it. Rank is the record's source position
     *       in data/source-priority.yaml for the record's category: existence sources first, then enrichment ones.
     *   <li>A place stays live only while some live record comes from an existence source for its category;
     *       otherwise it is retired (never deleted, so saved lists keep working). A returning record revives it.
     *   <li>Rows are only rewritten when something changed, so {@code updated_at} means "really changed".
     * </ul>
     *
     * Runs once per run, single-threaded, after every batch has committed, so the result does not depend on the
     * order batches committed in.
     */
    private static void refreshPlaces(JdbcClient tx, String source, List<SourcePriority.Role> roles) {
        tx.sql("""
                CREATE TEMP TABLE source_role (category text, source text, rank int, existence boolean, name_rank int,
                                               PRIMARY KEY (category, source)) ON COMMIT DROP
                """).update();
        for (SourcePriority.Role role : roles) {
            tx.sql("INSERT INTO source_role VALUES (?, ?, ?, ?, ?)")
                    .params(role.category(), role.source(), role.rank(), role.existence(), role.nameRank())
                    .update();
        }
        tx.sql("""
                CREATE TEMP TABLE touched ON COMMIT DROP AS
                SELECT DISTINCT place_id FROM place.place_source_link WHERE source = ?
                """).param(source).update();
        tx.sql("""
                WITH ranked AS (
                    SELECT l.place_id, r.source, r.source_key, r.name, r.category, r.geom, r.address, r.phone,
                           r.website, coalesce(sr.rank, 1000) AS rank, coalesce(sr.name_rank, 1000) AS name_rank,
                           coalesce(sr.existence, false) AS existence
                    FROM touched t
                    JOIN place.place_source_link l ON l.place_id = t.place_id
                    JOIN place.source_record r ON r.source = l.source AND r.source_key = l.source_key
                                                AND r.retired_at IS NULL
                    LEFT JOIN source_role sr ON sr.category = r.category AND sr.source = r.source),
                best AS (
                    SELECT place_id,
                           (array_agg(name ORDER BY name_rank, rank, source, source_key))[1] AS name,
                           (array_agg(category ORDER BY rank, source, source_key)
                               FILTER (WHERE category IS NOT NULL))[1] AS category,
                           (array_agg(geom ORDER BY rank, source, source_key) FILTER (WHERE geom IS NOT NULL))[1] AS geom,
                           (array_agg(address ORDER BY rank, source, source_key)
                               FILTER (WHERE address IS NOT NULL))[1] AS address,
                           (array_agg(phone ORDER BY rank, source, source_key) FILTER (WHERE phone IS NOT NULL))[1] AS phone,
                           (array_agg(website ORDER BY rank, source, source_key)
                               FILTER (WHERE website IS NOT NULL))[1] AS website,
                           bool_or(existence) AS established
                    FROM ranked
                    GROUP BY place_id)
                UPDATE place.place p
                SET name = b.name, category = coalesce(b.category, p.category), geom = coalesce(b.geom, p.geom),
                    address = b.address, phone = b.phone, website = b.website,
                    retired_at = CASE WHEN b.established THEN NULL ELSE coalesce(p.retired_at, now()) END,
                    updated_at = now()
                FROM best b
                WHERE p.place_id = b.place_id
                  AND ((p.name, p.category, p.address, p.phone, p.website)
                           IS DISTINCT FROM (b.name, coalesce(b.category, p.category), b.address, b.phone, b.website)
                       OR ST_AsBinary(p.geom) IS DISTINCT FROM ST_AsBinary(coalesce(b.geom, p.geom))
                       OR (p.retired_at IS NULL) IS DISTINCT FROM b.established)
                """).update();
        // Touched places with no live record left at all (the update above only saw places that still have one).
        tx.sql("""
                UPDATE place.place p SET retired_at = now(), updated_at = now()
                WHERE p.retired_at IS NULL
                  AND p.place_id IN (SELECT place_id FROM touched)
                  AND NOT EXISTS (SELECT 1 FROM place.place_source_link l
                                  JOIN place.source_record r ON r.source = l.source AND r.source_key = l.source_key
                                  WHERE l.place_id = p.place_id AND r.retired_at IS NULL)
                """).update();
    }

    private static void restoreAutoCommit(Connection c) {
        try {
            c.setAutoCommit(true);
        } catch (SQLException ignored) {
            // the connection is gone, and the lock with it; the caller already has the error
        }
    }

    private static void failInTransaction(JdbcClient tx, long runId, int rejected, String error) {
        tx.sql("UPDATE ingest.run SET status = 'FAILED', rejected_rows = ?, error = ?, finished_at = now() WHERE run_id = ?")
                .params(rejected, error, runId)
                .update();
    }
}
