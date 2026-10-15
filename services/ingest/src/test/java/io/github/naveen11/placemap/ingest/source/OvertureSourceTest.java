package io.github.naveen11.placemap.ingest.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.naveen11.placemap.ingest.TestData;
import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real DuckDB extract against a small synthetic GeoParquet file with Overture's schema (made-up places, so the
 * repository carries no third-party data). The S3 location is swapped for the local file.
 */
class OvertureSourceTest {

    @TempDir
    Path dir;

    private final OvertureSource adapter = new OvertureSource(TestData.area(), TestData.nativeCalls());
    private Path parquet;

    @BeforeEach
    void writeFixture() throws Exception {
        parquet = dir.resolve("places.parquet");
        try (var c = DriverManager.getConnection("jdbc:duckdb:"); var s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE place AS SELECT * FROM (VALUES
                      %s, %s, %s, %s, %s, %s, %s, %s
                    ) t(id, names, confidence, websites, phones, addresses, sources, operating_status, basic_category,
                        taxonomy, bbox, brand)
                    """.formatted(
                    place("b-cafe", "Corner Cafe", 0.95, "open", "cafe", "cafe", -71.0656, 42.3550),
                    place("a-grocer", "Harbor Market", 0.71, "open", "shopping", "grocery_store", -71.0995, 42.3876),
                    place("c-watertown", "Watertown Diner", 0.99, "open", "restaurant", "diner", -71.1828, 42.3651),
                    place("d-closed", "Gone Bistro", 0.92, "permanently_closed", "restaurant", "bistro", -71.0600, 42.3560),
                    place("e-dentist", "Smile Dental", 0.97, "open", "dentist", "dentist", -71.0610, 42.3570),
                    place("f-worcester", "Far Away Cafe", 0.99, "open", "cafe", "cafe", -71.8023, 42.2626),
                    place("g-pharmacy", "Main Pharmacy", 0.55, "open", "pharmacy_and_drug_store", "pharmacy", -71.1056, 42.3736),
                    "('h-noname', {'primary': NULL}, 0.9, [], [], [], [{'dataset': 'meta'}], 'open', 'cafe', "
                            + "{'primary': 'cafe'}, {'xmin': -71.07, 'xmax': -71.07, 'ymin': 42.35, 'ymax': 42.35}, "
                            + "{'wikidata': NULL})"));
            s.execute("COPY place TO '%s' (FORMAT parquet)".formatted(parquet));
        }
    }

    private static String place(String id, String name, double confidence, String status, String basic, String taxonomy,
            double lon, double lat) {
        return ("('%s', {'primary': '%s'}, %s, ['https://%s.example'], ['+16175550100'], "
                + "[{'freeform': '1 Main St', 'locality': 'Boston', 'postcode': '02108'}], "
                + "[{'dataset': 'meta'}, {'dataset': 'Foursquare'}, {'dataset': 'meta'}], '%s', '%s', {'primary': '%s'}, "
                + "{'xmin': %s, 'xmax': %s, 'ymin': %s, 'ymax': %s}, {'wikidata': 'Q1'})")
                .formatted(id, name, confidence, id, status, basic, taxonomy, lon, lon, lat, lat);
    }

    @Test
    void extractsTheEnvelopeSortedById() throws Exception {
        Path snapshot = fetch();

        List<String> lines = Files.readAllLines(snapshot);
        assertThat(lines.getFirst()).isEqualTo(String.join(",", adapter.columns()));
        assertThat(lines.subList(1, lines.size())).extracting(l -> l.substring(0, l.indexOf(',')))
                .containsExactly("a-grocer", "b-cafe", "c-watertown", "d-closed", "e-dentist", "g-pharmacy", "h-noname");
        // Worcester is outside the four towns' envelope, so DuckDB never returned it.
        CsvRow cafe = TestData.rows(snapshot).get(1);
        assertThat(cafe.get("lon")).isEqualTo("-71.0656");
        assertThat(cafe.get("website")).isEqualTo("https://b-cafe.example");
        assertThat(cafe.get("datasets")).isEqualTo("Foursquare;meta");
    }

    @Test
    void theSameReleaseGivesAByteIdenticalSnapshot() throws Exception {
        byte[] first = Files.readAllBytes(fetch());
        byte[] second = Files.readAllBytes(fetch());

        assertThat(second).isEqualTo(first);
    }

    @Test
    void keepsMappedPlacesAndSkipsTheRest() throws Exception {
        List<CsvRow> rows = TestData.rows(fetch());
        ParseContext context = TestData.parseContext();

        Map<String, String> outcome = rows.stream().collect(Collectors.toMap(adapter::key, row ->
                switch (adapter.parse(row, context)) {
                    case RowResult.Accepted a -> "accepted:" + a.row().category();
                    case RowResult.Rejected r -> "rejected:" + r.reason();
                    case RowResult.Skipped s -> "skipped:" + s.reason();
                }));

        assertThat(outcome).containsExactlyInAnyOrderEntriesOf(Map.of(
                "a-grocer", "accepted:grocery",       // via taxonomy_primary alone: the fields are alternatives
                "b-cafe", "accepted:food",
                "c-watertown", "skipped:outside the coverage area", // in the envelope, not in a town
                "d-closed", "skipped:permanently closed",
                "e-dentist", "skipped:no app category",
                "g-pharmacy", "accepted:pharmacy",     // low confidence is the matcher's business, not the parser's
                "h-noname", "skipped:no name"));
    }

    @Test
    void keepsConfidenceAndProvidersForMatchingAndAttribution() throws Exception {
        CsvRow pharmacy = TestData.rows(fetch()).stream().filter(r -> "g-pharmacy".equals(r.get("id"))).findFirst().orElseThrow();

        SourceRow record = ((RowResult.Accepted) adapter.parse(pharmacy, TestData.parseContext())).row();

        assertThat(record).extracting(SourceRow::name, SourceRow::address, SourceRow::postcode, SourceRow::phone)
                .containsExactly("Main Pharmacy", "1 Main St", "02108", "+16175550100");
        assertThat(record.attrs()).containsEntry("confidence", "0.55")
                .containsEntry("datasets", "Foursquare;meta")
                .containsEntry("brand_wikidata", "Q1");
        assertThat(List.of(record)).extracting(SourceRow::lon, SourceRow::lat).containsExactly(tuple(-71.1056, 42.3736));
    }

    private Path fetch() throws Exception {
        var source = new SourceDefinition(OvertureSource.ID, "Overture", true, "existence",
                new SourceDefinition.Access("geoparquet_s3", parquet.toString(), null), "monthly", "EPSG:4326", List.of());
        Path target = Files.createTempFile(dir, "snapshot", ".csv");
        adapter.fetch(source, target, new HttpDownloader());
        return target;
    }
}
