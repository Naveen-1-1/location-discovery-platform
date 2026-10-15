package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.ingest.source.InspectionLine;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;

/**
 * Writes a batch's inspection history inside the batch's transaction (the caller's, which {@code jdbc} joins). Every statement is safe to
 * repeat and gives the same result whatever order batches commit in, because a record's lines can fall into two
 * batches that commit in parallel:
 *
 * <ul>
 *   <li>inspections and violation lines are inserted only if new (their natural keys are unique);
 *   <li>a violation code's description converges on the greatest one seen, not the last one written.
 * </ul>
 *
 * Re-importing an unchanged history therefore writes almost nothing.
 */
final class InspectionWriter {

    private static final String UPSERT_CODE = """
            INSERT INTO place.violation_code (source, code, description) VALUES (?, ?, ?)
            ON CONFLICT (source, code) DO UPDATE SET description = EXCLUDED.description
            WHERE place.violation_code.description IS NULL OR EXCLUDED.description > place.violation_code.description
            """;
    private static final String INSERT_INSPECTION = """
            INSERT INTO place.inspection (source, source_key, inspected_at, result, created_run) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (source, source_key, inspected_at, result) DO NOTHING
            """;
    private static final String FIND_INSPECTIONS = """
            SELECT i.inspection_id, i.source_key, i.inspected_at, i.result
            FROM place.inspection i
            JOIN unnest(?::text[], ?::text[], ?::text[]) AS k(source_key, inspected_at, result)
              ON i.source_key = k.source_key AND i.inspected_at = k.inspected_at::timestamptz AND i.result = k.result
            WHERE i.source = ?
            """;
    private static final String INSERT_VIOLATION = """
            INSERT INTO place.inspection_violation
                (inspection_id, line_hash, code, level, status, cited_at, status_date, comment)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (inspection_id, line_hash) DO NOTHING
            """;

    private record InspectionKey(String recordKey, Instant at, String result) {
        static InspectionKey of(InspectionLine line) {
            return new InspectionKey(line.recordKey(), line.inspectedAt().toInstant(), line.result());
        }
    }

    private static final Comparator<InspectionKey> ORDER = Comparator.comparing(InspectionKey::recordKey)
            .thenComparing(InspectionKey::at).thenComparing(InspectionKey::result);

    private InspectionWriter() {
    }

    static void write(JdbcTemplate jdbc, String source, long runId, List<InspectionLine> lines) {
        if (lines.isEmpty()) {
            return;
        }
        // Sorted, so concurrent batches take row locks in the same order (fewer deadlocks).
        var codes = new TreeMap<String, String>();
        var inspections = new TreeMap<InspectionKey, OffsetDateTime>(ORDER);
        for (InspectionLine line : lines) {
            inspections.putIfAbsent(InspectionKey.of(line), line.inspectedAt());
            InspectionLine.Violation v = line.violation();
            if (v != null && v.code() != null) {
                codes.merge(v.code(), v.description() == null ? "" : v.description(),
                        (a, b) -> a.compareTo(b) >= 0 ? a : b);
            }
        }
        if (!codes.isEmpty()) {
            jdbc.batchUpdate(UPSERT_CODE, codes.entrySet(), codes.size(), (ps, code) -> {
                ps.setString(1, source);
                ps.setString(2, code.getKey());
                ps.setString(3, code.getValue().isEmpty() ? null : code.getValue());
            });
        }
        jdbc.batchUpdate(INSERT_INSPECTION, inspections.entrySet(), inspections.size(), (ps, inspection) -> {
            ps.setString(1, source);
            ps.setString(2, inspection.getKey().recordKey());
            ps.setObject(3, inspection.getValue());
            ps.setString(4, inspection.getKey().result());
            ps.setLong(5, runId);
        });
        Map<InspectionKey, Long> ids = findIds(jdbc, source, inspections.keySet());

        var violations = new ArrayList<InspectionLine>();
        for (InspectionLine line : lines) {
            if (line.violation() != null) {
                if (!ids.containsKey(InspectionKey.of(line))) {
                    throw new IllegalStateException("inspection row missing right after insert: "
                            + InspectionKey.of(line));
                }
                violations.add(line);
            }
        }
        if (violations.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT_VIOLATION, violations, violations.size(), (ps, line) -> {
            InspectionLine.Violation v = line.violation();
            ps.setLong(1, ids.get(InspectionKey.of(line)));
            ps.setBytes(2, v.lineHash());
            ps.setString(3, v.code());
            if (v.level() == null) {
                ps.setNull(4, Types.SMALLINT);
            } else {
                ps.setShort(4, v.level().shortValue());
            }
            ps.setString(5, v.status());
            ps.setObject(6, v.citedAt());
            ps.setObject(7, v.statusDate());
            ps.setString(8, v.comment());
        });
    }

    private static Map<InspectionKey, Long> findIds(JdbcTemplate jdbc, String source, Iterable<InspectionKey> keys) {
        var recordKeys = new ArrayList<String>();
        var times = new ArrayList<String>();
        var results = new ArrayList<String>();
        for (InspectionKey key : keys) {
            recordKeys.add(key.recordKey());
            times.add(key.at().toString()); // ISO-8601 instant, read back by Postgres as timestamptz
            results.add(key.result());
        }
        var ids = new HashMap<InspectionKey, Long>();
        jdbc.query(FIND_INSPECTIONS, rs -> {
            var key = new InspectionKey(rs.getString(2), rs.getObject(3, OffsetDateTime.class).toInstant(),
                    rs.getString(4));
            ids.put(key, rs.getLong(1));
        }, new SqlArrayValue("text", recordKeys.toArray()), new SqlArrayValue("text", times.toArray()),
                new SqlArrayValue("text", results.toArray()), source);
        return ids;
    }
}
