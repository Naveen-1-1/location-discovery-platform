package io.github.naveen11.placemap.ingest.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static io.github.naveen11.placemap.ingest.snapshot.RowRole.DUPLICATE;
import static io.github.naveen11.placemap.ingest.snapshot.RowRole.HISTORY_ONLY;
import static io.github.naveen11.placemap.ingest.snapshot.RowRole.RECORD;

import io.github.naveen11.placemap.ingest.TestData;
import io.github.naveen11.placemap.ingest.snapshot.RawBatch;
import io.github.naveen11.placemap.ingest.snapshot.RowRole;
import io.github.naveen11.placemap.ingest.source.BostonActiveFoodLicenses;
import io.github.naveen11.placemap.ingest.source.CsvRow;
import io.github.naveen11.placemap.ingest.source.ParseContext;
import io.github.naveen11.placemap.ingest.source.RowResult;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParseWorkerTest {

    private final BostonActiveFoodLicenses adapter = new BostonActiveFoodLicenses();

    @Test
    void acceptsGoodRowsRejectsDuplicatesAndQuarantinesRowsThatThrow() throws Exception {
        List<CsvRow> rows = TestData.rows(TestData.BOSTON_LICENCES).subList(0, 5);
        var throwing = new BostonActiveFoodLicenses() {
            @Override
            public RowResult parse(CsvRow row, ParseContext context) {
                if ("149 Eat Street".equals(row.get("businessname"))) {
                    throw new IllegalArgumentException("surprise");
                }
                return super.parse(row, context);
            }
        };
        var batch = new RawBatch(7, 70, rows, new RowRole[] {RECORD, RECORD, RECORD, DUPLICATE, RECORD});

        ParsedBatch parsed = new ParseWorker(throwing, TestData.parseContext()).process(batch);

        assertThat(parsed.seq()).isEqualTo(7);
        assertThat(parsed.rows()).isEqualTo(5);
        assertThat(parsed.records()).hasSize(3);
        assertThat(parsed.rejects()).extracting(ParsedBatch.Reject::rowNo, ParsedBatch.Reject::reason).containsExactly(
                tuple(1, "parse error: java.lang.IllegalArgumentException: surprise"),
                tuple(3, "repeated source key (the first row wins)"));
        assertThat(parsed.rejects().getFirst().record()).containsEntry("businessname", "149 Eat Street");
    }

    @Test
    void checksumDependsOnlyOnTheRawRows() throws Exception {
        List<CsvRow> rows = TestData.rows(TestData.BOSTON_LICENCES).subList(0, 6);
        var worker = new ParseWorker(adapter, TestData.parseContext());

        String a = worker.process(new RawBatch(0, 0, rows, records(6))).checksum();
        String b = new ParseWorker(adapter, TestData.parseContext()).process(new RawBatch(0, 0, rows, records(6))).checksum();
        String other = worker.process(new RawBatch(0, 0, rows.subList(0, 5), records(5))).checksum();

        assertThat(a).hasSize(64).isEqualTo(b).isNotEqualTo(other);
    }

    private static RowRole[] records(int n) {
        var roles = new RowRole[n];
        Arrays.fill(roles, RECORD);
        return roles;
    }

    @Test
    void historyOnlyRowsAddNoRecord() throws Exception {
        List<CsvRow> rows = TestData.rows(TestData.BOSTON_LICENCES).subList(0, 3);

        ParsedBatch parsed = new ParseWorker(adapter, TestData.parseContext())
                .process(new RawBatch(0, 0, rows, new RowRole[] {HISTORY_ONLY, RECORD, HISTORY_ONLY}));

        assertThat(parsed.records()).singleElement().satisfies(r -> assertThat(r.name()).isEqualTo("149 Eat Street"));
        assertThat(parsed.rejects()).isEmpty();
        assertThat(parsed.skipped()).isZero();
    }

    @Test
    void retriesOnlyErrorsThatAnotherAttemptCanFix() {
        assertThat(BatchWriter.isRetryable(new SQLException("deadlock", "40P01"))).isTrue();
        assertThat(BatchWriter.isRetryable(new SQLException("serialization", "40001"))).isTrue();
        assertThat(BatchWriter.isRetryable(new SQLException("connection reset", "08006"))).isTrue();
        assertThat(BatchWriter.isRetryable(new SQLException("terminating connection", "57P01"))).isTrue();
        assertThat(BatchWriter.isRetryable(new SQLTransientConnectionException("pool timeout"))).isTrue();
        assertThat(BatchWriter.isRetryable(new SQLException("syntax error", "42601"))).isFalse();
        assertThat(BatchWriter.isRetryable(new SQLException("foreign key", "23503"))).isFalse();
    }

    @Test
    void backoffGrowsWithJitterAndIsCapped() {
        for (int i = 0; i < 100; i++) {
            assertThat(BatchWriter.backoffMillis(1)).isBetween(100L, 300L);
            assertThat(BatchWriter.backoffMillis(3)).isBetween(400L, 1_200L);
            assertThat(BatchWriter.backoffMillis(10)).isBetween(2_500L, 7_500L);
        }
    }
}
