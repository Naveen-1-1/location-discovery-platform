package io.github.naveen11.placemap.ingest.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naveen11.placemap.ingest.TestData;
import io.github.naveen11.placemap.ingest.snapshot.RowRole;
import io.github.naveen11.placemap.ingest.snapshot.SnapshotScan;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The two inspection-history sources on real rows (public domain): 36 Boston lines for 3 licences, 11 Cambridge lines
 * for 3 establishments.
 */
class InspectionSourcesTest {

    private static final Path BOSTON = Path.of("src/test/resources/fixtures/boston_food_inspections.csv").toAbsolutePath();
    private static final Path CAMBRIDGE =
            Path.of("src/test/resources/fixtures/cambridge_sanitary_inspections.csv").toAbsolutePath();

    private final BostonFoodInspections boston = new BostonFoodInspections();
    private final CambridgeSanitaryInspections cambridge = new CambridgeSanitaryInspections();

    @Test
    void neverRequestsPersonalFields() {
        assertThat(boston.columns())
                .doesNotContainAnyElementsOf(TestData.catalog().get(BostonFoodInspections.ID).dropFields())
                .doesNotContain("legalowner", "namefirst", "namelast");
    }

    @Test
    void eachLicencesNewestLineDescribesTheEstablishment() throws Exception {
        List<CsvRow> rows = TestData.rows(BOSTON);
        SnapshotScan scan = SnapshotScan.of(BOSTON, boston);

        Map<String, SourceRow> records = IntStream.range(0, rows.size())
                .filter(i -> scan.role(i) == RowRole.RECORD)
                .mapToObj(i -> ((RowResult.Accepted) boston.parse(rows.get(i), TestData.parseContext())).row())
                .collect(Collectors.toMap(SourceRow::key, r -> r));

        assertThat(records).containsOnlyKeys("384048", "526046", "545262");
        assertThat(records.get("384048")).extracting(SourceRow::name, SourceRow::category)
                .containsExactly("Hudson (store 1888)", "grocery"); // active Retail Food: may create a grocery
        assertThat(records.get("526046")).extracting(SourceRow::name, SourceRow::category)
                .containsExactly("Tea Station Coffee", "food");
        assertThat(records.get("545262").address()).doesNotContain("  ");
        assertThat(scan.duplicates().isEmpty()).isTrue();
    }

    @Test
    void everyLineIsHistory() throws Exception {
        List<InspectionLine> lines = TestData.rows(BOSTON).stream().map(r -> boston.inspection(r).orElseThrow()).toList();

        assertThat(lines).hasSize(36);
        assertThat(lines.stream().map(l -> l.recordKey() + l.inspectedAt() + l.result()).distinct()).hasSize(20);
        List<InspectionLine.Violation> violations = lines.stream().map(InspectionLine::violation)
                .filter(v -> v != null).toList();
        assertThat(violations).hasSize(23);
        assertThat(violations).allSatisfy(v -> {
            assertThat(v.level()).isBetween(1, 3);
            assertThat(v.status()).isIn("Pass", "Fail");
        });
        assertThat(lines.getFirst().inspectedAt()).isEqualTo(OffsetDateTime.parse("2019-02-25T15:25:43Z"));
    }

    @Test
    void cambridgeEstablishmentsAreKeyedByNameAndAddress() throws Exception {
        List<CsvRow> rows = TestData.rows(CAMBRIDGE);
        SnapshotScan scan = SnapshotScan.of(CAMBRIDGE, cambridge);

        List<String> records = IntStream.range(0, rows.size()).filter(i -> scan.role(i) == RowRole.RECORD)
                .mapToObj(i -> cambridge.key(rows.get(i))).sorted().toList();
        List<InspectionLine> lines = rows.stream().map(r -> cambridge.inspection(r).orElseThrow()).toList();

        assertThat(records).containsExactly("Dado|955 Massachusetts Ave, Cambridge, MA 02139", "Lone Star Taco Bar|",
                "Lone Star Taco Bar|635 Cambridge St, Cambridge, MA 02141",
                "Ma Magoos Pizza|6 Concord Ln, Cambridge, MA 02138"); // its cases lack a start date: submission date used
        assertThat(lines.stream().map(l -> l.recordKey() + l.inspectedAt()).distinct()).hasSize(9);
        assertThat(lines.stream().map(InspectionLine::violation).filter(v -> v != null))
                .hasSize(24)
                .allSatisfy(v -> assertThat(v.status()).as("Cambridge lists only violations found").isEqualTo("Fail"));
        SourceRow lonestar = ((RowResult.Accepted) cambridge.parse(rows.get(IntStream.range(0, rows.size())
                .filter(i -> scan.role(i) == RowRole.RECORD && cambridge.key(rows.get(i)).endsWith("02141"))
                .findFirst().orElseThrow()), TestData.parseContext())).row();
        assertThat(lonestar).extracting(SourceRow::name, SourceRow::address, SourceRow::postcode)
                .containsExactly("Lone Star Taco Bar", "635 Cambridge St", "02141");
    }

    @Test
    void cambridgeLinesWithNoBusinessAreSkippedNotRejected() throws Exception {
        String header = String.join(",", cambridge.columns());
        CsvRow orphan = TestData.rows(header + "\n,,,,,,,,,,4-601.11,Equipment,,\n").getFirst();

        assertThat(cambridge.inspection(orphan)).isEmpty();
        assertThat(cambridge.parse(orphan, TestData.parseContext())).isInstanceOf(RowResult.Skipped.class);
    }

    @Test
    void longCommentsAreCutTo280Characters() {
        var violation = new InspectionLine.Violation("4-601.11", "Equipment", null, "Fail", null, null, "x".repeat(972));

        assertThat(violation.comment()).hasSize(InspectionLine.MAX_COMMENT);
        assertThat(violation.lineHash()).hasSize(16);
    }

    @Test
    void unusableHistoryLinesAreRejectedWithAReason() throws Exception {
        String header = String.join(",", boston.columns());
        CsvRow noDate = TestData.rows(header + "\nCafe,,123,,,Active,FS,Eating & Drinking,HE_Pass,,,,,,,,,1 MAIN,BOSTON,MA,02108,1,\n")
                .getFirst();

        assertThatThrownBy(() -> boston.inspection(noDate)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("missing inspection time");
    }

    @Test
    void readsBothCitiesDateFormats() {
        assertThat(Timestamps.parse("2018-03-20 14:54:25+00")).isEqualTo(OffsetDateTime.parse("2018-03-20T14:54:25Z"));
        assertThat(Timestamps.parse("2023-08-09 16:00:26.99+00")).isEqualTo(OffsetDateTime.parse("2023-08-09T16:00:26.99Z"));
        assertThat(Timestamps.parse("2022-03-31T00:00:00.000")).isEqualTo(OffsetDateTime.parse("2022-03-31T00:00-04:00"));
        assertThat(Timestamps.parse("2022-01-31")).isEqualTo(OffsetDateTime.parse("2022-01-31T00:00-05:00"));
        assertThat(Timestamps.parse(null)).isNull();
        assertThatThrownBy(() -> Timestamps.parse("yesterday")).isInstanceOf(IllegalArgumentException.class);
        assertThat(BostonFoodInspections.level("**")).isEqualTo(2);
        assertThat(BostonFoodInspections.level("x")).isNull();
        assertThat(Optional.ofNullable(BostonFoodInspections.level(null))).isEmpty();
    }
}
