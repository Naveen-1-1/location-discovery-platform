package io.github.naveen11.placemap.ingest.source;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.ingest.OfflineHttp;
import io.github.naveen11.placemap.ingest.TestData;
import io.github.naveen11.placemap.ingest.snapshot.RowRole;
import io.github.naveen11.placemap.ingest.snapshot.SnapshotScan;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Cambridge permits adapter and the Socrata fetcher, on 14 real rows (3 establishments, 2020–2026 renewals). */
class CambridgeFoodPermitsTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/cambridge_food_permits.csv").toAbsolutePath();

    @TempDir
    Path dir;

    /** Serves the fixture as Socrata would, honouring $limit and $offset, and records each request. */
    private final class FakeSocrata extends OfflineHttp {
        final List<String> queries = new ArrayList<>();

        @Override
        public void download(URI uri, Path target) throws IOException {
            String query = URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
            queries.add(query);
            int limit = Integer.parseInt(query.replaceAll(".*\\$limit=(\\d+).*", "$1"));
            int offset = Integer.parseInt(query.replaceAll(".*\\$offset=(\\d+).*", "$1"));
            List<String> lines = Files.readAllLines(FIXTURE);
            var page = new ArrayList<String>();
            page.add(lines.getFirst());
            page.addAll(lines.subList(1, lines.size()).stream().skip(offset).limit(limit).toList());
            Files.write(target, page);
        }
    }

    private Path fetch(CambridgeFoodPermits adapter, FakeSocrata http) throws Exception {
        Path target = dir.resolve("snapshot-" + http.hashCode() + ".csv");
        adapter.fetch(TestData.catalog().get(CambridgeFoodPermits.ID), target, http);
        return target;
    }

    @Test
    void asksOnlyForItsColumnsSortedByAUniqueKey() throws Exception {
        var http = new FakeSocrata();

        Path snapshot = fetch(new CambridgeFoodPermits(), http);

        assertThat(http.queries).singleElement().satisfies(q -> {
            assertThat(q).startsWith("$select=" + String.join(",", new CambridgeFoodPermits().columns()));
            assertThat(q).contains("$order=id,issue_date,viewpoint_id").doesNotContain("applicant_name");
        });
        assertThat(TestData.rows(snapshot)).hasSize(14);
    }

    @Test
    void pagesAreJoinedIntoTheSameSnapshot() throws Exception {
        var paged = new CambridgeFoodPermits() {
            @Override
            protected int pageSize() {
                return 5;
            }
        };
        var http = new FakeSocrata();

        byte[] onePage = Files.readAllBytes(fetch(new CambridgeFoodPermits(), new FakeSocrata()));
        byte[] fivePerPage = Files.readAllBytes(fetch(paged, http));

        assertThat(http.queries).hasSize(3).extracting(q -> q.replaceAll(".*\\$offset=", ""))
                .containsExactly("0", "5", "10");
        assertThat(fivePerPage).isEqualTo(onePage);
    }

    @Test
    void theNewestRenewalDescribesTheEstablishment() throws Exception {
        var adapter = new CambridgeFoodPermits();
        Path snapshot = fetch(adapter, new FakeSocrata());
        List<CsvRow> rows = TestData.rows(snapshot);

        SnapshotScan scan = SnapshotScan.of(snapshot, adapter);

        Map<String, String> describing = IntStream.range(0, rows.size())
                .filter(i -> scan.role(i) == RowRole.RECORD).boxed()
                .collect(Collectors.toMap(i -> rows.get(i).get("id"), i -> rows.get(i).get("issue_date")));
        assertThat(describing).containsOnlyKeys("100034", "16348", "16373");
        assertThat(describing.get("100034")).isEqualTo("2026-01-05T00:00:00.000");
        assertThat(scan.duplicates().isEmpty()).as("renewals are history, not duplicates").isTrue();
        assertThat(IntStream.range(0, rows.size()).filter(i -> scan.role(i) == RowRole.HISTORY_ONLY).count()).isEqualTo(11);
    }

    @Test
    void parsesTheRecordWithHoursAndCategory() throws Exception {
        var adapter = new CambridgeFoodPermits();
        CsvRow newest = TestData.rows(fetch(adapter, new FakeSocrata())).stream()
                .filter(r -> "2026-01-05T00:00:00.000".equals(r.get("issue_date"))).findFirst().orElseThrow();

        SourceRow record = ((RowResult.Accepted) adapter.parse(newest, TestData.parseContext())).row();

        assertThat(record).extracting(SourceRow::key, SourceRow::name, SourceRow::address, SourceRow::postcode,
                SourceRow::category).containsExactly("100034", "Frank's Steak House", "2310 Massachusetts Ave", "02140", "food");
        assertThat(record.attrs()).containsKeys("monday_hours", "sunday_hours", "number_of_seats")
                .containsEntry("business_name", "Will George Inc")
                .doesNotContainKey("applicant_name");
    }
}
