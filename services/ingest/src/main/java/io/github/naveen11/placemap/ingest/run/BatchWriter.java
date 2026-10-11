package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.concurrent.ContiguousWatermark;
import io.github.naveen11.placemap.ingest.match.MatchDecision;
import io.github.naveen11.placemap.ingest.pipeline.Stage;
import io.github.naveen11.placemap.ingest.source.SourceRow;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Upsert stage: commits one batch per transaction (ADR 0020). Each worker thread holds its own connection.
 *
 * <ol>
 *   <li>Upsert the records, sorted by key so concurrent batches lock rows in the same order. Unchanged rows (same
 *       hash) are not rewritten.
 *   <li>Apply the matching decisions: new links, new places, review entries.
 *   <li>Add any inspection history the batch carries.
 *   <li>Insert the rejects and the {@code batch_done} checkpoint.
 *   <li>Commit, then mark the batch in the watermark.
 * </ol>
 *
 * <p>Deadlocks, serialization failures and lost connections are retried with jittered backoff, at most 5 attempts.
 * Every statement is safe to repeat, so a retry after a commit whose acknowledgement was lost changes nothing: the
 * upsert is idempotent, links and checkpoints ignore rows that exist, and a place is only created if the record
 * has no link yet. Such a repeat is visible: its checkpoint already exists. It is logged and counted, which is how crash
 * test C2 shows that it really cut connections between a commit and its acknowledgement.
 */
final class BatchWriter implements Stage.Worker<MatchedBatch, Void> {

    private static final Logger log = LoggerFactory.getLogger(BatchWriter.class);

    static final int MAX_ATTEMPTS = 5;

    private static final String UPSERT_RECORD = """
            INSERT INTO place.source_record AS r
                (source, source_key, name, category, geom, address, postcode, phone, website, attrs, row_hash,
                 created_run, changed_run)
            VALUES (?, ?, ?, ?, ST_GeogFromText(?), ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
            ON CONFLICT (source, source_key) DO UPDATE SET
                name = EXCLUDED.name, category = EXCLUDED.category, geom = EXCLUDED.geom,
                address = EXCLUDED.address, postcode = EXCLUDED.postcode, phone = EXCLUDED.phone,
                website = EXCLUDED.website, attrs = EXCLUDED.attrs, row_hash = EXCLUDED.row_hash,
                changed_run = EXCLUDED.changed_run, retired_at = NULL
            WHERE r.row_hash IS DISTINCT FROM EXCLUDED.row_hash OR r.retired_at IS NOT NULL
            """;
    private static final String INSERT_LINK = """
            INSERT INTO place.place_source_link (source, source_key, place_id, method, score, linked_run)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (source, source_key) DO NOTHING
            """;
    // The NOT EXISTS makes creation idempotent: repeating the batch after a lost commit acknowledgement finds the
    // link and creates nothing.
    private static final String CREATE_PLACE = """
            WITH created AS (
                INSERT INTO place.place (name, category, geom, address, phone, website)
                SELECT ?, ?, ST_GeogFromText(?), ?, ?, ?
                WHERE NOT EXISTS (SELECT 1 FROM place.place_source_link WHERE source = ? AND source_key = ?)
                RETURNING place_id)
            INSERT INTO place.place_source_link (source, source_key, place_id, method, linked_run)
            SELECT ?, ?, place_id, 'created', ? FROM created
            """;
    private static final String UPSERT_REVIEW = """
            INSERT INTO place.match_review (source, source_key, place_id, score, run_id) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (source, source_key, place_id) DO UPDATE SET score = EXCLUDED.score, run_id = EXCLUDED.run_id
            """;
    private static final String INSERT_REJECT = """
            INSERT INTO ingest.reject (run_id, seq, row_no, reason, record) VALUES (?, ?, ?, ?, CAST(? AS jsonb))
            ON CONFLICT DO NOTHING
            """;
    private static final String INSERT_BATCH_DONE = """
            INSERT INTO ingest.batch_done (run_id, seq, rows, rejected, skipped, checksum, keys)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (run_id, seq) DO NOTHING
            """;

    private final DataSource dataSource;
    private final long runId;
    private final String source;
    private final ContiguousWatermark watermark;
    private final IngestMetrics.RunMetrics metrics;
    private final JsonMapper json = new JsonMapper();
    private Connection connection;

    BatchWriter(DataSource dataSource, long runId, String source, ContiguousWatermark watermark,
            IngestMetrics.RunMetrics metrics) {
        this.dataSource = dataSource;
        this.runId = runId;
        this.source = source;
        this.watermark = watermark;
        this.metrics = metrics;
    }

    @Override
    public Void process(MatchedBatch matched) throws SQLException, InterruptedException {
        int seq = matched.batch().seq();
        for (int attempt = 1; ; attempt++) {
            try {
                if (!write(matched)) {
                    metrics.recommitted();
                    log.info("ingest {}: batch {} of run {} was already committed by an earlier attempt whose "
                            + "acknowledgement was lost; repeating it changed nothing", source, seq, runId);
                }
                break;
            } catch (SQLException e) {
                if (!isRetryable(e) || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                metrics.retried();
                discardConnection(); // after a lost connection the old one is useless; after a deadlock it is cheap
                long backoff = backoffMillis(attempt);
                log.warn("ingest {}: batch {} of run {} failed on attempt {} of {} ({}); retrying in {} ms", source, seq,
                        runId, attempt, MAX_ATTEMPTS, e.toString(), backoff);
                Thread.sleep(backoff);
            }
        }
        watermark.markDone(seq);
        metrics.committed(matched);
        return null;
    }

    /** Writes and commits the batch; false if its checkpoint already existed (an earlier attempt committed it). */
    private boolean write(MatchedBatch matched) throws SQLException {
        ParsedBatch batch = matched.batch();
        List<SourceRow> records = batch.records();
        List<Integer> byKey = IntStream.range(0, records.size()).boxed()
                .sorted(Comparator.comparing(i -> records.get(i).key()))
                .toList();
        Connection c = connection();
        boolean inserted;
        try {
            try (var upsert = c.prepareStatement(UPSERT_RECORD)) {
                for (int i : byKey) {
                    SourceRow row = records.get(i);
                    int p = 1;
                    upsert.setString(p++, source);
                    upsert.setString(p++, row.key());
                    upsert.setString(p++, row.name());
                    upsert.setString(p++, row.category());
                    upsert.setString(p++, row.ewkt());
                    upsert.setString(p++, row.address());
                    upsert.setString(p++, row.postcode());
                    upsert.setString(p++, row.phone());
                    upsert.setString(p++, row.website());
                    upsert.setString(p++, json.writeValueAsString(row.attrs()));
                    upsert.setString(p++, row.rowHash());
                    upsert.setLong(p++, runId);
                    upsert.setLong(p, runId);
                    upsert.addBatch();
                }
                upsert.executeBatch();
            }
            applyDecisions(c, matched, byKey);
            InspectionWriter.write(c, source, runId, batch.inspections());
            try (var reject = c.prepareStatement(INSERT_REJECT)) {
                for (ParsedBatch.Reject r : batch.rejects()) {
                    reject.setLong(1, runId);
                    reject.setInt(2, batch.seq());
                    reject.setInt(3, r.rowNo());
                    reject.setString(4, r.reason());
                    reject.setString(5, json.writeValueAsString(r.record()));
                    reject.addBatch();
                }
                reject.executeBatch();
            }
            try (var done = c.prepareStatement(INSERT_BATCH_DONE)) {
                done.setLong(1, runId);
                done.setInt(2, batch.seq());
                done.setInt(3, batch.rows());
                done.setInt(4, batch.rejects().size());
                done.setInt(5, batch.skipped());
                done.setString(6, batch.checksum());
                done.setArray(7, c.createArrayOf("text", byKey.stream().map(i -> records.get(i).key()).toArray()));
                inserted = done.executeUpdate() == 1;
            }
            c.commit();
            return inserted;
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly(c);
            throw e;
        }
    }

    private void applyDecisions(Connection c, MatchedBatch matched, List<Integer> byKey) throws SQLException {
        List<SourceRow> records = matched.batch().records();
        try (var link = c.prepareStatement(INSERT_LINK);
                var create = c.prepareStatement(CREATE_PLACE);
                var review = c.prepareStatement(UPSERT_REVIEW)) {
            for (int i : byKey) {
                SourceRow row = records.get(i);
                switch (matched.decisions().get(i)) {
                    case MatchDecision.Link l -> {
                        link.setString(1, source);
                        link.setString(2, row.key());
                        link.setLong(3, l.placeId());
                        link.setString(4, l.method());
                        link.setDouble(5, l.score());
                        link.setLong(6, runId);
                        link.addBatch();
                    }
                    case MatchDecision.Create ignored -> {
                        int p = 1;
                        create.setString(p++, row.name());
                        create.setString(p++, row.category());
                        create.setString(p++, row.ewkt());
                        create.setString(p++, row.address());
                        create.setString(p++, row.phone());
                        create.setString(p++, row.website());
                        create.setString(p++, source);
                        create.setString(p++, row.key());
                        create.setString(p++, source);
                        create.setString(p++, row.key());
                        create.setLong(p, runId);
                        create.addBatch();
                    }
                    case MatchDecision.Review r -> {
                        review.setString(1, source);
                        review.setString(2, row.key());
                        review.setLong(3, r.placeId());
                        review.setDouble(4, r.score());
                        review.setLong(5, runId);
                        review.addBatch();
                    }
                    case MatchDecision.Keep ignored -> {
                        // linked in an earlier run
                    }
                    case MatchDecision.Unlinked ignored -> {
                        // stays a record without a place
                    }
                }
            }
            link.executeBatch(); // an empty batch is a no-op
            create.executeBatch();
            review.executeBatch();
        }
    }

    private Connection connection() throws SQLException {
        if (connection == null) {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
        }
        return connection;
    }

    private void discardConnection() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // already broken; the pool replaces it
            }
            connection = null;
        }
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // the connection is probably gone; the server rolls back on its own
        }
    }

    static boolean isRetryable(SQLException e) {
        String state = e.getSQLState();
        return e instanceof SQLTransientException
                || e instanceof SQLRecoverableException
                || "40001".equals(state)                    // serialization failure
                || "40P01".equals(state)                    // deadlock detected
                || (state != null && state.startsWith("08")) // connection exception
                || "57P01".equals(state) || "57P02".equals(state) || "57P03".equals(state); // server restarting
    }

    /** 200 ms, 400 ms, 800 ms, … capped at 5 s, each scaled by a random factor in [0.5, 1.5). */
    static long backoffMillis(int attempt) {
        long base = Math.min(5_000, 200L << (attempt - 1));
        return (long) (base * (0.5 + ThreadLocalRandom.current().nextDouble()));
    }

    @Override
    public void close() {
        discardConnection();
    }
}
