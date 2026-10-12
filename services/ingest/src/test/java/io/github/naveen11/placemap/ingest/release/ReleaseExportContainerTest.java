package io.github.naveen11.placemap.ingest.release;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.ingest.TestData;
import io.github.naveen11.placemap.ingest.catalog.SourcePriority;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The release export against the real migrations (ADR 0044), with places made up to cover each rule: an open place
 * with an inspection, two transit stops with no open existence record, a bank named from OSM, a place the app shows
 * under a display-only record but an open one also lists, and two that are retired one way or another. Needs Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReleaseExportContainerTest {

    private static final Path POSTGIS_IMAGE_DIR = Path.of("../../infra/docker/postgis").toAbsolutePath().normalize();
    private static final Map<String, SourceTerms> TERMS = SourceTerms.load(TestData.REPO.resolve("data/sources.yaml"));
    private static final SourcePriority PRIORITY = SourcePriority.load(TestData.REPO.resolve("data/source-priority.yaml"));
    private static final Path LICENCES = TestData.REPO.resolve("data/licences");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 11);

    @Container
    static final GenericContainer<?> POSTGIS = new GenericContainer<>(
                    new ImageFromDockerfile("placemap/postgis-test", false).withFileFromPath(".", POSTGIS_IMAGE_DIR))
            .withEnv("POSTGRES_DB", "placemap")
            .withEnv("POSTGRES_PASSWORD", "test")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));

    private static DataSource dataSource;
    private static final Map<String, Long> PLACES = new LinkedHashMap<>();

    @TempDir
    Path releaseDir;

    @BeforeAll
    static void seed() {
        String url = "jdbc:postgresql://%s:%d/placemap".formatted(POSTGIS.getHost(), POSTGIS.getMappedPort(5432));
        dataSource = new DriverManagerDataSource(url, "postgres", "test");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        var jdbc = JdbcClient.create(dataSource);
        jdbc.sql("INSERT INTO ingest.run (source, status) VALUES ('test', 'COMPLETE')").update();

        // An open café: Overture (with two upstream providers), OSM, a Boston inspection and a licence.
        place("cafe", "Corner Café", "food", null);
        record("cafe", "overture_places", "o-cafe", "Corner Café", "food", -71.0600, 42.3560, "12 Main St", null,
                "{\"datasets\": \"Overture;meta;Foursquare\", \"confidence\": \"0.95\"}", false, "created");
        record("cafe", "osm_geofabrik_massachusetts", "node/1", "Corner Cafe", "food", -71.0601, 42.3561, null,
                "+1 617-555-0100", "{}", false, "score");
        record("cafe", "boston_food_inspections", "LIC-1", "CORNER CAFE LLC", null, -71.0600, 42.3560, "12 MAIN ST",
                null, "{\"licstatus\": \"Active\"}", false, "score");
        record("cafe", "boston_active_food_licenses", "p1|CORNER CAFE LLC|FS|12 MAIN ST", "CORNER CAFE LLC", "food",
                -71.0600, 42.3560, "12 MAIN ST", null, "{}", false, "score");
        jdbc.sql("""
                INSERT INTO place.place_facts (place_id, accepts_snap, active_licence, inspection, inspected_at,
                                               failed_checks_12m, hours, hours_source)
                VALUES (?, false, true, 'passed', '2026-09-01T10:00:00Z', 0, 'Mon–Fri 07:00–23:00', 'osm')
                """).param(PLACES.get("cafe")).update();
        long inspection = jdbc.sql("""
                        INSERT INTO place.inspection (source, source_key, inspected_at, result, created_run)
                        VALUES ('boston_food_inspections', 'LIC-1', '2026-09-01T10:00:00Z', 'HE_Pass', 1)
                        RETURNING inspection_id""").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO place.inspection_violation (inspection_id, line_hash, code, level, status, comment)
                VALUES (?, '\\x01', '1-1', 1, 'Pass', 'Inspector Jane Doe spoke with the owner')
                """).param(inspection).update();
        jdbc.sql("""
                INSERT INTO place.inspection_summary (source, source_key, latest_result, latest_at, inspections,
                                                      failed_checks_12m)
                VALUES ('boston_food_inspections', 'LIC-1', 'HE_Pass', '2026-09-01T10:00:00Z', 1, 0)
                """).update();

        // A T stop known only to MBTA, and one whose only open record is a licence without a category.
        place("park_street", "Park Street", "transit", null);
        record("park_street", "mbta_gtfs", "place-pktrm", "Park Street", "transit", -71.0624, 42.3564, null, null,
                "{}", false, "created");
        place("south_station", "South Station", "transit", null);
        record("south_station", "mbta_gtfs", "place-sstat", "South Station", "transit", -71.0552, 42.3523, null, null,
                "{}", false, "created");
        record("south_station", "boston_licensing_board_licenses", "LB-9", "SOUTH STATION NEWS", null, -71.0552,
                42.3523, "700 ATLANTIC AVE", null, "{\"status\": \"Active\"}", false, "score");

        // A bank: FDIC decides it exists, OSM gives the name on the door.
        place("bank", "Chase", "bank", null);
        record("bank", "fdic_bank_locations", "UNI-1", "JPMorgan Chase Bank, National Association", "bank", -71.0570,
                42.3550, "1 Federal St", null, "{}", false, "created");
        record("bank", "osm_geofabrik_massachusetts", "node/2", "Chase", "bank", -71.0571, 42.3551, null, null, "{}",
                false, "score");

        // The app shows this one as a Bluebikes dock, but Overture also lists a café there.
        place("dock", "Main St Dock", "bike_share", null);
        record("dock", "bluebikes_station_information", "bb-1", "Main St Dock", "bike_share", -71.0700, 42.3500, null,
                "+1 855-948-3000", "{}", false, "created");
        record("dock", "overture_places", "o-coffee", "Dock Coffee", "food", -71.0701, 42.3501, null, null,
                "{\"datasets\": \"Overture;meta\"}", false, "score");

        // Retired places, and a place whose only open record is retired.
        place("closed", "Old Diner", "food", "2026-09-01T00:00:00Z");
        record("closed", "osm_geofabrik_massachusetts", "node/3", "Old Diner", "food", -71.0800, 42.3400, null, null,
                "{}", false, "created");
        place("gone", "Gone Cafe", "transit", null);
        record("gone", "osm_geofabrik_massachusetts", "node/4", "Gone Cafe", "food", -71.0810, 42.3410, null, null,
                "{}", true, "created");
        record("gone", "mbta_gtfs", "place-gone", "Gone Stop", "transit", -71.0810, 42.3410, null, null, "{}", false,
                "score");
    }

    @Test
    void releasesOpenPlacesWithOpenValuesOnly() throws Exception {
        ReleaseExport.Summary summary = ReleaseExport.build(dataSource, TERMS, PRIORITY, LICENCES, releaseDir, DATE);

        Path folder = releaseDir.resolve("2026-10-11");
        assertThat(summary.folder()).isEqualTo(folder);
        assertThat(summary.places()).isEqualTo(3);
        assertThat(summary.byCategory()).containsExactly(Map.entry("bank", 1), Map.entry("food", 2));
        assertThat(summary.overtureProviders()).containsExactly("Foursquare", "Overture", "meta");
        assertThat(ReleaseCheck.check(folder, TERMS, LICENCES)).isEmpty();
        try (Stream<Path> files = Files.list(releaseDir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("2026-10-11"); // no partial left
        }

        List<Map<String, Object>> places = rows(folder, """
                SELECT place_id, name, category, address, phone, inspection, inspection_source, inspection_record_id,
                       hours, CAST(geometry AS VARCHAR) AS point
                FROM '%s' ORDER BY place_id""".formatted(ReleaseExport.sql(folder.resolve(ReleaseExport.PLACES))));
        assertThat(places).extracting(p -> p.get("place_id"))
                .containsExactly(PLACES.get("cafe"), PLACES.get("bank"), PLACES.get("dock"));
        assertThat(places.get(0)).containsEntry("name", "Corner Café").containsEntry("address", "12 Main St")
                .containsEntry("phone", "+1 617-555-0100").containsEntry("inspection", "passed")
                .containsEntry("inspection_source", "boston_food_inspections")
                .containsEntry("inspection_record_id", "LIC-1").containsEntry("hours", "Mon–Fri 07:00–23:00")
                .containsEntry("point", "POINT (-71.06 42.356)");
        assertThat(places.get(1)).containsEntry("name", "Chase").containsEntry("address", "1 Federal St");
        // Overture's café, never the dock: no name, category or phone from Bluebikes.
        assertThat(places.get(2)).containsEntry("name", "Dock Coffee").containsEntry("category", "food")
                .containsEntry("phone", null).containsEntry("point", "POINT (-71.0701 42.3501)");

        List<Map<String, Object>> sources = rows(folder, "SELECT place_id, source, record_id, link FROM '%s'"
                .formatted(ReleaseExport.sql(folder.resolve(ReleaseExport.PLACE_SOURCES))));
        assertThat(sources).extracting(s -> s.get("source") + ":" + s.get("record_id")).containsExactlyInAnyOrder(
                "boston_active_food_licenses:p1|CORNER CAFE LLC|FS|12 MAIN ST", "boston_food_inspections:LIC-1",
                "osm_geofabrik_massachusetts:node/1", "overture_places:o-cafe", "fdic_bank_locations:UNI-1",
                "osm_geofabrik_massachusetts:node/2", "overture_places:o-coffee");

        List<Map<String, Object>> comments = rows(folder, "SELECT * FROM '%s' t WHERE contains(CAST(t AS VARCHAR), 'Jane')"
                .formatted(ReleaseExport.sql(folder.resolve(ReleaseExport.PLACES))));
        assertThat(comments).isEmpty();

        String attribution = Files.readString(folder.resolve(ReleaseDocs.ATTRIBUTION));
        assertThat(attribution).contains("`overture_places`", "Data from Meta.", "Data from Foursquare.",
                "`fdic_bank_locations`", "`boston_food_inspections`", "`boston_active_food_licenses`")
                .doesNotContain("mbta", "MBTA", "bluebikes", "Bluebikes", "licensing_board", "Microsoft");
        assertThat(folder.resolve("licences/Foursquare-NOTICE.txt")).exists();

        // A second build the same day replaces the first.
        ReleaseExport.build(dataSource, TERMS, PRIORITY, LICENCES, releaseDir, DATE);
        assertThat(ReleaseCheck.check(folder, TERMS, LICENCES)).isEmpty();
    }

    private static void place(String name, String shown, String category, String retiredAt) {
        long id = JdbcClient.create(dataSource).sql("""
                        INSERT INTO place.place (name, category, geom, retired_at)
                        VALUES (?, ?, ST_GeogFromText('SRID=4326;POINT(-71.06 42.35)'), CAST(? AS timestamptz))
                        RETURNING place_id""")
                .params(shown, category, retiredAt).query(Long.class).single();
        PLACES.put(name, id);
    }

    private static void record(String place, String source, String key, String name, String category, double lon,
            double lat, String address, String phone, String attrs, boolean retired, String method) {
        var jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                INSERT INTO place.source_record (source, source_key, name, category, geom, address, phone, attrs,
                                                 row_hash, created_run, changed_run, retired_at)
                VALUES (?, ?, ?, ?, ST_GeogFromText(?), ?, ?, CAST(? AS jsonb), 'h', 1, 1,
                        CASE WHEN ? THEN now() END)""")
                .params(source, key, name, category, "SRID=4326;POINT(%s %s)".formatted(lon, lat), address, phone,
                        attrs, retired)
                .update();
        jdbc.sql("INSERT INTO place.place_source_link (source, source_key, place_id, method) VALUES (?, ?, ?, ?)")
                .params(source, key, PLACES.get(place), method).update();
    }

    private static List<Map<String, Object>> rows(Path folder, String sql) throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        try (var duck = DriverManager.getConnection("jdbc:duckdb:"); var s = duck.createStatement();
                var rs = s.executeQuery(sql)) {
            while (rs.next()) {
                var row = new LinkedHashMap<String, Object>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }
}
