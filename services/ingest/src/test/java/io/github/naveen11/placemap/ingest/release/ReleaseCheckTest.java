package io.github.naveen11.placemap.ingest.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naveen11.placemap.ingest.TestData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The release's own checks and documents (ADR 0044) on small hand-made releases, written through the export's own
 * tables and COPY statements. No database needed; the export's query is tested in {@code ReleaseExportContainerTest}.
 */
class ReleaseCheckTest {

    private static final Map<String, SourceTerms> TERMS = SourceTerms.load(TestData.REPO.resolve("data/sources.yaml"));
    private static final Path LICENCES = TestData.REPO.resolve("data/licences");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 11);

    @TempDir
    Path folder;

    @BeforeEach
    void build() throws Exception {
        release(List.of(
                "(1, 'Corner Cafe', 'food', '12 Main St', NULL, NULL, false, true, 'Mon–Fri 07:00–23:00', 'osm', NULL,"
                        + " 'passed', TIMESTAMPTZ '2026-09-01 10:00:00+00', 'boston_food_inspections', 'LIC-1',"
                        + " 'POINT(-71.06 42.356)')",
                "(2, 'Chase Bank', 'bank', NULL, NULL, NULL, false, false, NULL, NULL, NULL, NULL, NULL, NULL, NULL,"
                        + " 'POINT(-71.07 42.35)')"),
                List.of("(1, 'overture_places', 'o-cafe', 'created', NULL)",
                        "(1, 'osm_geofabrik_massachusetts', 'node/1', 'score', 0.91)",
                        "(1, 'boston_food_inspections', 'LIC-1', 'score', 0.85)",
                        "(2, 'fdic_bank_locations', '123', 'created', NULL)",
                        "(2, 'osm_geofabrik_massachusetts', 'node/2', 'score', 0.88)"),
                Set.of("meta", "Foursquare", "Overture"));
    }

    @Test
    void aWellFormedReleasePasses() throws Exception {
        assertThat(ReleaseCheck.check(folder, TERMS, LICENCES)).isEmpty();
        assertThat(Files.readString(folder.resolve(ReleaseDocs.LICENSE)))
                .startsWith("## Open Data Commons Open Database License (ODbL)");
        assertThat(Files.readString(folder.resolve(ReleaseDocs.SUMS)).lines().map(l -> l.substring(66)))
                .containsExactly("ATTRIBUTION.md", "LICENSE", "METHOD.md", "licences/Apache-2.0.txt",
                        "licences/CDLA-Permissive-2.0.txt", "licences/Foursquare-NOTICE.txt", "place_sources.parquet",
                        "places.parquet");
    }

    @Test
    void attributionCreditsOnlyTheSourcesAndProvidersInTheData() throws IOException {
        String attribution = Files.readString(folder.resolve(ReleaseDocs.ATTRIBUTION));

        assertThat(attribution)
                .contains("Open Database License (ODbL) 1.0")
                .contains("### OpenStreetMap - Massachusetts extract", "- Credit: © OpenStreetMap contributors")
                .contains("- Credit: Overture Maps Foundation, overturemaps.org")
                .contains("- Data from Meta. (CDLA Permissive 2.0; text in `licences/CDLA-Permissive-2.0.txt`)")
                .contains("Data from Foursquare. Copyright 2024 Foursquare Labs", "notice in `licences/Foursquare-NOTICE.txt`")
                .contains("`fdic_bank_locations`", "Credit (courtesy; the licence asks for none): Federal Deposit")
                .contains("- Records in this release: 2")
                .doesNotContain("Microsoft", "Krick", "AllThePlaces", "MBTA", "Bluebikes", "SNAP");
        assertThat(Files.readString(folder.resolve(ReleaseDocs.METHOD)))
                .contains("release 2026-10-11. 2 places", "built from 5 source records")
                .contains("| bank | 1 |", "| food | 1 |")
                .contains("| OpenStreetMap - Massachusetts extract (`osm_geofabrik_massachusetts`) | 2 |")
                .doesNotContain("{{");
    }

    @Test
    void recordsFromADisplayOnlySourceFailTheCheck() throws Exception {
        rewrite("INSERT INTO place_sources VALUES (2, 'mbta_gtfs', 'place-pktrm', 'score', 0.9)");

        assertThat(ReleaseCheck.check(folder, TERMS, LICENCES)).contains(
                "1 records from a source not marked \"yes\"",
                "1 values in place_sources naming the display-only source mbta_gtfs");
    }

    @Test
    void aPlaceWithoutAnOpenRecordFailsTheCheck() throws Exception {
        rewrite("DELETE FROM place_sources WHERE place_id = 2");

        assertThat(ReleaseCheck.check(folder, TERMS, LICENCES)).containsExactly("1 places without a source record");
    }

    @Test
    void aPersonalDataColumnFailsTheCheck() throws Exception {
        rewrite("ALTER TABLE places ADD COLUMN inspector_comment VARCHAR");

        assertThat(ReleaseCheck.check(folder, TERMS, LICENCES)).anySatisfy(p -> assertThat(p)
                .contains("places.inspector_comment looks like personal data or free text"));
    }

    @Test
    void aPointOutsideTheAreaFailsTheCheck() throws Exception {
        rewrite("UPDATE places SET wkt = 'POINT(-75.5 35.2)' WHERE place_id = 2"); // the Atlantic off the Carolinas

        assertThat(ReleaseCheck.check(folder, TERMS, LICENCES))
                .anySatisfy(p -> assertThat(p).startsWith("places.parquet has points outside the area"));
    }

    @Test
    void aChangedOrExtraFileFailsTheCheck() throws Exception {
        Files.writeString(folder.resolve(ReleaseDocs.METHOD), "edited\n");
        Files.writeString(folder.resolve("notes.txt"), "stray\n");

        assertThat(ReleaseCheck.check(folder, TERMS, LICENCES))
                .contains("METHOD.md does not match its SHA256SUMS line", "notes.txt is not in SHA256SUMS");
    }

    @Test
    void anOvertureProviderNobodyAuditedStopsTheRelease() {
        assertThatThrownBy(() -> ReleaseDocs.providers(TERMS, Set.of("meta", "NewProvider")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[NewProvider]");
    }

    @Test
    void shipsOnlyTheLicenceTextsItsProvidersNeed() {
        assertThat(ReleaseDocs.shippedTexts(ReleaseDocs.providers(TERMS, Set.of("AllThePlaces", "Overture")))).isEmpty();
        assertThat(ReleaseDocs.shippedTexts(ReleaseDocs.providers(TERMS, Set.of("BrightQuery"))))
                .containsExactly("CDLA-Permissive-2.0.txt");
    }

    @Test
    void everyImportedSourceIsReleasableOrDisplayOnly() {
        // The plan's rule: an imported source whose terms are unclear would block the release.
        assertThat(TERMS.values()).filteredOn(SourceTerms::isEnabled)
                .allSatisfy(s -> assertThat(s.redistribution()).as(s.id()).isIn("yes", "display-only"));
        assertThat(TERMS.values()).filteredOn(SourceTerms::displayOnly).extracting(SourceTerms::id)
                .containsExactlyInAnyOrder("mbta_gtfs", "mbta_gtfs_rt", "mbta_v3_api",
                        "bluebikes_station_information", "bluebikes_station_status");
    }

    /** Writes the release folder from rows in the export's own tables, as the export does. */
    private void release(List<String> places, List<String> sources, Set<String> providers) throws Exception {
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:")) {
            ReleaseExport.createTables(duck);
            try (var s = duck.createStatement()) {
                s.execute("INSERT INTO places VALUES " + String.join(", ", places));
                s.execute("INSERT INTO place_sources VALUES " + String.join(", ", sources));
            }
            ReleaseExport.write(duck, folder);
            docs(duck, providers);
        }
    }

    /** Applies a change to the written tables and rebuilds the files around them, checksums included. */
    private void rewrite(String change) throws Exception {
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:"); var s = duck.createStatement()) {
            s.execute("CREATE TABLE places AS SELECT * EXCLUDE (geometry), CAST(geometry AS VARCHAR) AS wkt FROM '"
                    + ReleaseExport.sql(folder.resolve(ReleaseExport.PLACES)) + "'");
            s.execute("CREATE TABLE place_sources AS FROM '" + ReleaseExport.sql(folder.resolve(ReleaseExport.PLACE_SOURCES)) + "'");
            s.execute(change);
            try (var files = Files.list(folder)) {
                for (Path file : files.toList()) {
                    ReleaseExport.deleteTree(file);
                }
            }
            if (change.contains("ADD COLUMN")) { // keep the extra column in place of the export's column list
                s.execute("ALTER TABLE places RENAME TO staged");
                s.execute("CREATE TABLE places AS SELECT * EXCLUDE (wkt, inspector_comment), inspector_comment, wkt FROM staged");
            }
            ReleaseExport.write(duck, folder);
            docs(duck, Set.of("meta", "Foursquare", "Overture"));
        }
    }

    private void docs(Connection duck, Set<String> providers) throws SQLException, IOException {
        var byCategory = new TreeMap<String, Integer>();
        var bySource = new TreeMap<String, Integer>();
        int places = 0;
        int records = 0;
        try (var s = duck.createStatement()) {
            try (var rows = s.executeQuery("SELECT category, count(*) FROM places GROUP BY 1")) {
                while (rows.next()) {
                    byCategory.put(rows.getString(1), rows.getInt(2));
                    places += rows.getInt(2);
                }
            }
            try (var rows = s.executeQuery("SELECT source, count(*) FROM place_sources GROUP BY 1")) {
                while (rows.next()) {
                    bySource.put(rows.getString(1), rows.getInt(2));
                    records += rows.getInt(2);
                }
            }
        }
        var summary = new ReleaseExport.Summary(folder, places, records, byCategory, bySource, new TreeSet<>(providers));
        ReleaseDocs.write(folder, TERMS, LICENCES, DATE, summary);
    }
}
