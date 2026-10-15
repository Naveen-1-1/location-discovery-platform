package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.ingest.match.MatchDecision;
import io.github.naveen11.placemap.ingest.source.SourceRow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Commits each batch in a transaction of its own, the import's only writes to the place tables before completion.
 *
 * <ol>
 *   <li>Upsert the records, sorted by key so concurrent batches lock rows in the same order. Unchanged rows (same
 *       hash) are not rewritten.
 *   <li>Apply the matching decisions: new links, new places, review entries.
 *   <li>Add any inspection history the batch carries.
 *   <li>Insert the rejects and the {@code batch_done} checkpoint, which is what tells a resumed run the batch is done.
 * </ol>
 *
 * <p>Deadlocks, serialization failures and lost connections are retried with jittered backoff, at most 5 attempts.
 * The retry wraps the whole transaction, commit included: after such an error Postgres has already aborted the
 * transaction (or its connection is gone), so only a new one can succeed. That is also why this writer, not Spring
 * Batch's step, owns the transaction: the step's own retry would run inside the aborted one.
 *
 * <p>Every statement is safe to repeat, so a retry after a commit whose acknowledgement was lost changes nothing: the
 * upsert is idempotent, links and checkpoints ignore rows that exist, and a place is only created if the record has no
 * link yet. Such a repeat is visible: its checkpoint already exists. It is logged and counted, which is how crash test
 * C2 shows that it really cut connections between a commit and its acknowledgement.
 */
class BatchWriter implements ItemWriter<MatchedBatch> {

    private static final Logger log = LoggerFactory.getLogger(BatchWriter.class);

    /** Deadlocks and lost connections ({@link DatabaseErrors}); 4 retries, 200 ms doubling to at most 5 s. */
    static final RetryPolicy RETRY = RetryPolicy.builder()
            .predicate(DatabaseErrors::isTransient)
            .maxRetries(4)
            .delay(Duration.ofMillis(200))
            .jitter(Duration.ofMillis(100))
            .multiplier(2)
            .maxDelay(Duration.ofSeconds(5))
            .build();

    private static final String UPSERT_RECORD = """
            INSERT INTO place.source_record AS r
                (source, source_key, name, category, geom, address, postcode, phone, website, attrs, row_hash,
                 created_run, changed_run, outline)
            VALUES (?, ?, ?, ?, ST_GeogFromText(?), ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ST_GeogFromText(?))
            ON CONFLICT (source, source_key) DO UPDATE SET
                name = EXCLUDED.name, category = EXCLUDED.category, geom = EXCLUDED.geom, outline = EXCLUDED.outline,
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

    private record Decided(SourceRow row, MatchDecision decision) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RetryTemplate retry = new RetryTemplate(RETRY);
    private final long runId;
    private final String source;
    private final IngestMetrics.RunMetrics metrics;
    private final JsonMapper json = new JsonMapper();

    BatchWriter(JdbcTemplate jdbc, TransactionTemplate transactions, long runId, String source,
            IngestMetrics.RunMetrics metrics) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.runId = runId;
        this.source = source;
        this.metrics = metrics;
    }

    @Override
    public void write(Chunk<? extends MatchedBatch> chunk) {
        for (MatchedBatch matched : chunk) {
            int seq = matched.batch().seq();
            retry.setRetryListener(new RetryListener() {
                @Override
                public void beforeRetry(RetryPolicy policy, Retryable<?> retryable, RetryState state) {
                    metrics.retried();
                    log.warn("ingest {}: batch {} of run {} failed on attempt {} of 5 ({}); retrying in a new "
                            + "transaction", source, seq, runId, state.getRetryCount(),
                            state.getLastException().toString());
                }
            });
            boolean first = retry.invoke(() -> transactions.execute(status -> write(matched)));
            if (!first) {
                metrics.recommitted();
                log.info("ingest {}: batch {} of run {} was already committed by an earlier attempt whose "
                        + "acknowledgement was lost; repeating it changed nothing", source, seq, runId);
            }
            metrics.committed(matched);
        }
    }

    /** Writes the batch in the current transaction; false if its checkpoint already existed (an earlier commit). */
    private boolean write(MatchedBatch matched) {
        ParsedBatch batch = matched.batch();
        var byKey = new ArrayList<Decided>(batch.records().size());
        for (int i = 0; i < batch.records().size(); i++) {
            byKey.add(new Decided(batch.records().get(i), matched.decisions().get(i)));
        }
        byKey.sort(Comparator.comparing(d -> d.row().key()));

        batchUpdate(UPSERT_RECORD, byKey, (ps, d) -> {
            SourceRow row = d.row();
            int p = 1;
            ps.setString(p++, source);
            ps.setString(p++, row.key());
            ps.setString(p++, row.name());
            ps.setString(p++, row.category());
            ps.setString(p++, row.ewkt());
            ps.setString(p++, row.address());
            ps.setString(p++, row.postcode());
            ps.setString(p++, row.phone());
            ps.setString(p++, row.website());
            ps.setString(p++, json.writeValueAsString(row.attrs()));
            ps.setString(p++, row.rowHash());
            ps.setLong(p++, runId);
            ps.setLong(p++, runId);
            ps.setString(p, row.outlineEwkt());
        });
        applyDecisions(byKey);
        InspectionWriter.write(jdbc, source, runId, batch.inspections());
        batchUpdate(INSERT_REJECT, batch.rejects(), (ps, r) -> {
            ps.setLong(1, runId);
            ps.setInt(2, batch.seq());
            ps.setInt(3, r.rowNo());
            ps.setString(4, r.reason());
            ps.setString(5, json.writeValueAsString(r.record()));
        });
        String[] keys = byKey.stream().map(d -> d.row().key()).toArray(String[]::new);
        return jdbc.update(INSERT_BATCH_DONE, runId, batch.seq(), batch.rows(), batch.rejects().size(), batch.skipped(),
                batch.checksum(), new SqlArrayValue("text", (Object[]) keys)) == 1;
    }

    private void applyDecisions(List<Decided> byKey) {
        var links = new ArrayList<Decided>();
        var creates = new ArrayList<Decided>();
        var reviews = new ArrayList<Decided>();
        for (Decided d : byKey) {
            switch (d.decision()) {
                case MatchDecision.Link ignored -> links.add(d);
                case MatchDecision.Create ignored -> creates.add(d);
                case MatchDecision.Review ignored -> reviews.add(d);
                case MatchDecision.Keep ignored -> {
                    // linked in an earlier run
                }
                case MatchDecision.Unlinked ignored -> {
                    // stays a record without a place
                }
            }
        }
        batchUpdate(INSERT_LINK, links, (ps, d) -> {
            var link = (MatchDecision.Link) d.decision();
            ps.setString(1, source);
            ps.setString(2, d.row().key());
            ps.setLong(3, link.placeId());
            ps.setString(4, link.method());
            ps.setDouble(5, link.score());
            ps.setLong(6, runId);
        });
        batchUpdate(CREATE_PLACE, creates, (ps, d) -> {
            SourceRow row = d.row();
            int p = 1;
            ps.setString(p++, row.name());
            ps.setString(p++, row.category());
            ps.setString(p++, row.ewkt());
            ps.setString(p++, row.address());
            ps.setString(p++, row.phone());
            ps.setString(p++, row.website());
            ps.setString(p++, source);
            ps.setString(p++, row.key());
            ps.setString(p++, source);
            ps.setString(p++, row.key());
            ps.setLong(p, runId);
        });
        batchUpdate(UPSERT_REVIEW, reviews, (ps, d) -> {
            var review = (MatchDecision.Review) d.decision();
            ps.setString(1, source);
            ps.setString(2, d.row().key());
            ps.setLong(3, review.placeId());
            ps.setDouble(4, review.score());
            ps.setLong(5, runId);
        });
    }

    /** One JDBC batch of all the rows (none: no statement). */
    private <T> void batchUpdate(String sql, List<T> rows, ParameterizedPreparedStatementSetter<T> values) {
        if (!rows.isEmpty()) {
            jdbc.batchUpdate(sql, rows, rows.size(), values);
        }
    }
}
