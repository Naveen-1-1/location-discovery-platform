package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.concurrent.ContiguousWatermark;
import io.github.naveen11.placemap.ingest.pipeline.Stage;
import io.github.naveen11.placemap.ingest.source.SourceRow;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Upsert stage: commits one batch per transaction (ADR 0020). Each worker thread holds its own connection.
 *
 * <ol>
 *   <li>Upsert the records, sorted by key so concurrent batches lock rows in the same order. Unchanged rows (same
 *       hash) are not rewritten.
 *   <li>Insert the rejects and the {@code batch_done} checkpoint.
 *   <li>Commit, then mark the batch in the watermark.
 * </ol>
 *
 * <p>Deadlocks, serialization failures and lost connections are retried with jittered backoff, at most 5 attempts.
 * A retry after a commit whose acknowledgement was lost is safe: the upsert is idempotent and the reject and
 * checkpoint inserts ignore rows that already exist.
 */
final class BatchWriter implements Stage.Worker<ParsedBatch, Void> {

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
    public Void process(ParsedBatch batch) throws SQLException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                write(batch);
                break;
            } catch (SQLException e) {
                if (!isRetryable(e) || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                metrics.retried();
                discardConnection(); // after a lost connection the old one is useless; after a deadlock it is cheap
                Thread.sleep(backoffMillis(attempt));
            }
        }
        watermark.markDone(batch.seq());
        metrics.committed(batch);
        return null;
    }

    private void write(ParsedBatch batch) throws SQLException {
        Connection c = connection();
        try {
            List<SourceRow> records = new ArrayList<>(batch.records());
            records.sort(Comparator.comparing(SourceRow::key));
            try (var upsert = c.prepareStatement(UPSERT_RECORD)) {
                for (SourceRow row : records) {
                    int i = 1;
                    upsert.setString(i++, source);
                    upsert.setString(i++, row.key());
                    upsert.setString(i++, row.name());
                    upsert.setString(i++, row.category());
                    upsert.setString(i++, row.ewkt());
                    upsert.setString(i++, row.address());
                    upsert.setString(i++, row.postcode());
                    upsert.setString(i++, row.phone());
                    upsert.setString(i++, row.website());
                    upsert.setString(i++, json.writeValueAsString(row.attrs()));
                    upsert.setString(i++, row.rowHash());
                    upsert.setLong(i++, runId);
                    upsert.setLong(i, runId);
                    upsert.addBatch();
                }
                upsert.executeBatch();
            }
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
                done.setArray(7, c.createArrayOf("text", records.stream().map(SourceRow::key).toArray()));
                done.executeUpdate();
            }
            c.commit();
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly(c);
            throw e;
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
