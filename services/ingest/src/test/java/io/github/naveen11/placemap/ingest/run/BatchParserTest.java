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
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BatchParserTest {

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

        ParsedBatch parsed = new BatchParser(throwing, TestData.parseContext()).parse(batch);

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
        var parser = new BatchParser(adapter, TestData.parseContext());

        String a = parser.parse(new RawBatch(0, 0, rows, records(6))).checksum();
        String b = new BatchParser(adapter, TestData.parseContext()).parse(new RawBatch(0, 0, rows, records(6))).checksum();
        String other = parser.parse(new RawBatch(0, 0, rows.subList(0, 5), records(5))).checksum();

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

        ParsedBatch parsed = new BatchParser(adapter, TestData.parseContext())
                .parse(new RawBatch(0, 0, rows, new RowRole[] {HISTORY_ONLY, RECORD, HISTORY_ONLY}));

        assertThat(parsed.records()).singleElement().satisfies(r -> assertThat(r.name()).isEqualTo("149 Eat Street"));
        assertThat(parsed.rejects()).isEmpty();
        assertThat(parsed.skipped()).isZero();
    }
}
