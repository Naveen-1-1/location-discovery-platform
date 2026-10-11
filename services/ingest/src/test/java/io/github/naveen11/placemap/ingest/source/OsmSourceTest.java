package io.github.naveen11.placemap.ingest.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.naveen11.placemap.ingest.TestData;
import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import tools.jackson.databind.json.JsonMapper;

class OsmSourceTest {

    private static final Path SAMPLE = Path.of("src/test/resources/fixtures/osm-sample.osm").toAbsolutePath();

    @TempDir
    Path dir;

    private final OsmSource adapter = new OsmSource(TestData.area(), TestData.categories(), "osmium");

    @Test
    void columnsAreTheCategoryTagsPlusTheUsefulExtras() {
        assertThat(adapter.columns()).startsWith("osm_type", "osm_id", "lon", "lat")
                .contains("amenity", "shop", "leisure", "healthcare", "name", "addr_street", "brand_wikidata");
    }

    /** The real osmium pipeline on a made-up file: extract to the envelope, filter by category tags, export. */
    @Test
    void osmiumKeepsOnlyCategorisedObjectsInTheEnvelope() throws Exception {
        assumeTrue(osmiumAvailable(), "osmium is not installed");

        Path snapshot = fetch();

        List<CsvRow> rows = TestData.rows(snapshot);
        assertThat(rows).extracting(adapter::key)
                .containsExactly("node/1", "node/2", "node/4", "node/5", "node/7", "way/10"); // bench filtered; Worcester cut
        try (var left = Files.list(dir)) {
            assertThat(left.map(p -> p.getFileName().toString()).toList())
                    .as("intermediate files are deleted").containsExactly("snapshot.csv");
        }
    }

    @Test
    void parsesNodesAndOutlines() throws Exception {
        assumeTrue(osmiumAvailable(), "osmium is not installed");
        List<CsvRow> rows = TestData.rows(fetch());
        ParseContext context = TestData.parseContext();

        Map<String, RowResult> results = rows.stream()
                .collect(Collectors.toMap(adapter::key, row -> adapter.parse(row, context)));

        assertThat(results.get("node/2")).isEqualTo(new RowResult.Skipped("no name"));
        SourceRow atm = ((RowResult.Accepted) results.get("node/4")).row();
        assertThat(atm).extracting(SourceRow::name, SourceRow::category).containsExactly("Bank of Example", "atm");
        assertThat(results.get("node/5")).isEqualTo(new RowResult.Skipped("outside the coverage area"));
        SourceRow cafe = ((RowResult.Accepted) results.get("node/1")).row();
        assertThat(cafe).extracting(SourceRow::name, SourceRow::category, SourceRow::address, SourceRow::postcode,
                SourceRow::website).containsExactly("Corner Cafe", "food", "12 Main Street", "02108", "https://cafe.example");
        assertThat(cafe.attrs()).containsEntry("amenity", "cafe").containsEntry("brand_wikidata", "Q1");
        SourceRow pharmacy = ((RowResult.Accepted) results.get("node/7")).row();
        assertThat(pharmacy.category()).isEqualTo("pharmacy"); // healthcare=pharmacy alone is enough
        assertThat(pharmacy.phone()).isEqualTo("+1 617 555 0100");
        SourceRow park = ((RowResult.Accepted) results.get("way/10")).row();
        assertThat(park.category()).isEqualTo("park");
        assertThat(park.lon()).isBetween(-71.0700, -71.0640);
        assertThat(park.lat()).isBetween(42.3530, 42.3570);
    }

    @Test
    void theSameExtractGivesAByteIdenticalSnapshot() throws Exception {
        assumeTrue(osmiumAvailable(), "osmium is not installed");

        byte[] first = Files.readAllBytes(fetch());
        byte[] second = Files.readAllBytes(fetch());

        assertThat(second).isEqualTo(first);
    }

    /** For an L-shaped building the centroid lies outside the outline; the representative point never does. */
    @Test
    void anOutlineIsLocatedInsideItselfEvenWhenItsCentroidIsNot() {
        var lShape = new JsonMapper().readTree("""
                {"type": "Polygon", "coordinates": [[[0, 0], [10, 0], [10, 1], [1, 1], [1, 10], [0, 10], [0, 0]]]}
                """);

        Coordinate point = OsmSource.representativePoint(lShape);

        boolean inFoot = point.x >= 0 && point.x <= 10 && point.y >= 0 && point.y <= 1;
        boolean inLeg = point.x >= 0 && point.x <= 1 && point.y >= 0 && point.y <= 10;
        assertThat(inFoot || inLeg).as("point %s lies inside the L", point).isTrue();
    }

    private Path fetch() throws Exception {
        var source = new SourceDefinition(OsmSource.ID, "OSM", true, "existence",
                new SourceDefinition.Access("osm_pbf", "https://example.test/extracts/sample.osm", null), "weekly",
                "EPSG:4326", List.of());
        var http = new HttpDownloader() {
            @Override
            public void download(URI uri, Path target) throws java.io.IOException {
                Files.copy(SAMPLE, target, StandardCopyOption.REPLACE_EXISTING);
            }
        };
        Path target = dir.resolve("snapshot.csv");
        adapter.fetch(source, target, http);
        return target;
    }

    private static boolean osmiumAvailable() {
        try {
            return new ProcessBuilder("osmium", "--version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
