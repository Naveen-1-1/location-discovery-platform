package io.github.naveen11.placemap.ingest.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.naveen11.placemap.ingest.OfflineHttp;
import io.github.naveen11.placemap.ingest.TestData;
import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.match.MatchSample;
import io.github.naveen11.placemap.ingest.run.RunCoordinator.Outcome;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import io.github.naveen11.placemap.ingest.source.BostonActiveFoodLicenses;
import io.github.naveen11.placemap.ingest.source.BostonOpenSpace;
import io.github.naveen11.placemap.ingest.source.OsmSource;
import io.github.naveen11.placemap.ingest.source.OvertureSource;
import io.github.naveen11.placemap.ingest.source.SourceAdapter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Overture, OSM and the Boston licences merged into places through the real adapters (DuckDB, osmium), the real
 * matcher and the real migrations. All places are made up. Needs Docker and osmium.
 *
 * <p>The scenario, around downtown Boston:
 * <ul>
 *   <li>A Phase 1 seed place already exists for Overture's "Corner Café" (handed over to the Overture importer).
 *   <li>OSM has the café, a market, a pharmacy and a coffee shop.
 *   <li>Overture has the same café (linked by score), the market with low confidence (corroborated by OSM), the
 *       pharmacy (linked by score), a confident new bakery (creates a place), a doubtful diner and a confident but
 *       uncorroborated pharmacy (both left unlinked), and a café near the coffee shop that is only borderline (review).
 *   <li>The licences have the café (linked) and a grill with no place nearby (never creates one: enrichment only).
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
class PlaceMatchingContainerTest {

    private static final Path POSTGIS_IMAGE_DIR = Path.of("../../infra/docker/postgis").toAbsolutePath().normalize();
    private static final String OSM = OsmSource.ID;
    private static final String OVERTURE = OvertureSource.ID;
    private static final String LICENCES = BostonActiveFoodLicenses.ID;
    private static final double METRE = 1.0 / 111_320; // degrees of latitude per metre

    private static final double[] CAFE = {-71.0600, 42.3560};
    private static final double[] MARKET = {-71.0560, 42.3600};
    private static final double[] PHARMACY = {-71.0620, 42.3520};
    private static final double[] COFFEE = {-71.0650, 42.3500};
    private static final double[] BAKERY = {-71.0700, 42.3480};
    private static final double[] DINER = {-71.0750, 42.3450};
    private static final double[] LONELY = {-71.0800, 42.3400};

    @Container
    static final GenericContainer<?> POSTGIS = new GenericContainer<>(
                    new ImageFromDockerfile("placemap/postgis-test", false).withFileFromPath(".", POSTGIS_IMAGE_DIR))
            .withEnv("POSTGRES_DB", "placemap")
            .withEnv("POSTGRES_PASSWORD", "test")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));

    private static DataSource dataSource;
    private static JdbcClient jdbc;

    @TempDir
    Path tmp;

    private Path osmFile;
    private Path overtureFile;
    private Path licenceFile;
    private Path openSpaceFile;
    private long seedPlace;

    @BeforeAll
    static void migrate() {
        String url = "jdbc:postgresql://%s:%d/placemap?reWriteBatchedInserts=true".formatted(POSTGIS.getHost(),
                POSTGIS.getMappedPort(5432));
        dataSource = new DriverManagerDataSource(url, "postgres", "test");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
    }

    @BeforeEach
    void reset() throws Exception {
        assumeTrue(osmiumAvailable(), "osmium is not installed");
        jdbc.sql("""
                TRUNCATE ingest.reject, ingest.batch_done, place.match_review, place.place_source_link,
                         place.source_record, place.place, ingest.run RESTART IDENTITY CASCADE
                """).update();
        osmFile = tmp.resolve("extract.osm");
        overtureFile = tmp.resolve("places.parquet");
        licenceFile = tmp.resolve("licences.csv");
        openSpaceFile = tmp.resolve("open_space.geojson");
        writeOsm(List.of(
                osmNode(1, CAFE, 0, "amenity", "cafe", "Corner Cafe", "12", "Main Street"),
                osmNode(2, MARKET, 0, "shop", "supermarket", "Harbor Market", null, null),
                osmNode(3, PHARMACY, 0, "amenity", "pharmacy", "Main Pharmacy", null, null),
                osmNode(4, COFFEE, 0, "amenity", "cafe", "Harbour Coffee", null, null)));
        writeOverture(defaultOverture());
        Files.writeString(licenceFile, String.join("\n",
                "businessname,dbaname,address,city,state,zip,licstatus,licensecat,descript,license_add_dt_tm,property_id,latitude,longitude",
                licence("Corner Cafe LLC", "Corner Cafe", "12 MAIN", "1", CAFE, 5),
                licence("Faraway Grill", "", "1 NOWHERE", "2", LONELY, 0)) + "\n");
        // What V3 makes of a Phase 1 seed place: a place linked to its Overture id.
        seedPlace = jdbc.sql("""
                        INSERT INTO place.place (name, category, geom)
                        VALUES ('Corner Cafe', 'food', ST_GeogFromText(?)) RETURNING place_id
                        """)
                .param("SRID=4326;POINT(%s %s)".formatted(CAFE[0], CAFE[1]))
                .query(Long.class)
                .single();
        jdbc.sql("INSERT INTO place.place_source_link (source, source_key, place_id, method) VALUES (?, 'o-cafe', ?, 'id')")
                .params(OVERTURE, seedPlace)
                .update();
    }

    private static List<String> defaultOverture() {
        return List.of(
                overture("o-cafe", "Corner Café", "cafe", "cafe", 0.95, CAFE, 15, "12 Main St"),
                overture("o-market", "Harbour Market", "shopping", "grocery_store", 0.70, MARKET, 90, null),
                overture("o-pharmacy", "Main Pharmacy Inc", "pharmacy_and_drug_store", "pharmacy", 0.99, PHARMACY, 10, null),
                overture("o-coffee", "Harbor Cafe", "cafe", "cafe", 0.95, COFFEE, 70, null),
                overture("o-bakery", "New Bakery", "cafe", "bakery", 0.95, BAKERY, 0, null),
                overture("o-diner", "Quiet Diner", "restaurant", "diner", 0.60, DINER, 0, null),
                overture("o-lonely", "Lonely Pharmacy", "pharmacy_and_drug_store", "pharmacy", 0.99, LONELY, 0, null));
    }

    @Test
    void mergesTheSourcesIntoPlacesByTheRules() {
        runAll();

        assertThat(liveCategories()).containsExactlyInAnyOrder("food", "grocery", "pharmacy", "food", "food");
        assertThat(placeOf(OVERTURE, "o-cafe")).as("the seed place kept its id").isEqualTo(seedPlace);
        assertThat(placeOf(OSM, "node/1")).isEqualTo(seedPlace);
        assertThat(placeOf(LICENCES, "1|Corner Cafe LLC|FS|12 MAIN")).isEqualTo(seedPlace);
        assertThat(method(OVERTURE, "o-market")).isEqualTo("corroborated");
        assertThat(placeOf(OVERTURE, "o-market")).isEqualTo(placeOf(OSM, "node/2"));
        assertThat(method(OVERTURE, "o-pharmacy")).isEqualTo("score");
        assertThat(method(OVERTURE, "o-bakery")).isEqualTo("created");
        assertThat(placeOf(OVERTURE, "o-diner")).as("doubtful, uncorroborated").isNull();
        assertThat(placeOf(OVERTURE, "o-lonely")).as("Overture pharmacies need corroboration").isNull();
        assertThat(placeOf(LICENCES, "2|Faraway Grill|FS|1 NOWHERE")).as("licences never create places").isNull();
        assertThat(placeOf(OVERTURE, "o-coffee")).as("borderline: waits for review").isNull();
        assertThat(jdbc.sql("SELECT place_id FROM place.match_review WHERE source = ? AND source_key = 'o-coffee'")
                .param(OVERTURE).query(Long.class).single()).isEqualTo(placeOf(OSM, "node/4"));
    }

    @Test
    void eachPlaceShowsItsBestRankedSourceFirst() {
        runAll();

        // Food and groceries rank Overture first, pharmacies rank OSM first (data/source-priority.yaml).
        assertThat(place(seedPlace)).containsEntry("name", "Corner Café").containsEntry("address", "12 Main St");
        assertThat(place(placeOf(OSM, "node/2"))).containsEntry("name", "Harbour Market");
        assertThat(place(placeOf(OSM, "node/3"))).containsEntry("name", "Main Pharmacy");
    }

    @Test
    void placeIdsStayTheSameAcrossRuns() {
        runAll();
        Map<String, Long> before = links();

        assertThat(run(OSM, true, 0.2)).isEqualTo(Outcome.COMPLETE);
        assertThat(run(OVERTURE, true, 0.2)).isEqualTo(Outcome.COMPLETE);
        assertThat(run(LICENCES, true, 0.2)).isEqualTo(Outcome.COMPLETE);

        assertThat(links()).isEqualTo(before);
        assertThat(liveCategories()).hasSize(5);
    }

    @Test
    void aPlaceIsRetiredOnlyWhenNoSourceThatEstablishesItRemains() throws Exception {
        runAll();
        long market = placeOf(OSM, "node/2");
        long bakery = placeOf(OVERTURE, "o-bakery");

        // OSM drops the market: Overture's record still establishes it (Overture is an existence source for groceries).
        writeOsm(List.of(
                osmNode(1, CAFE, 0, "amenity", "cafe", "Corner Cafe", "12", "Main Street"),
                osmNode(3, PHARMACY, 0, "amenity", "pharmacy", "Main Pharmacy", null, null),
                osmNode(4, COFFEE, 0, "amenity", "cafe", "Harbour Coffee", null, null)));
        assertThat(run(OSM, false, 1.0)).isEqualTo(Outcome.COMPLETE);
        assertThat(place(market)).containsEntry("retired", false).containsEntry("name", "Harbour Market");

        // Overture drops the bakery: nothing else establishes it, so it is retired (not deleted)…
        var withoutBakery = new ArrayList<>(defaultOverture());
        withoutBakery.removeIf(row -> row.contains("'o-bakery'"));
        writeOverture(withoutBakery);
        assertThat(run(OVERTURE, false, 1.0)).isEqualTo(Outcome.COMPLETE);
        assertThat(place(bakery)).containsEntry("retired", true);

        // …and revived under the same id when it comes back.
        writeOverture(defaultOverture());
        assertThat(run(OVERTURE, false, 1.0)).isEqualTo(Outcome.COMPLETE);
        assertThat(place(bakery)).containsEntry("retired", false);
        assertThat(placeOf(OVERTURE, "o-bakery")).isEqualTo(bakery);
    }

    /**
     * A commit fails part-way through the Overture run, after the batch that created the bakery committed. On resume
     * the index must not contain that new place, or later records could match it and the result would depend on
     * where the crash happened. The resumed run must end exactly where a clean run ends.
     */
    @Test
    void aMatchingRunResumedAfterACrashEndsWhereACleanRunEnds() throws Exception {
        assertThat(run(OSM, false, 0.2)).isEqualTo(Outcome.COMPLETE);
        assertThat(run(OVERTURE, false, 0.2)).isEqualTo(Outcome.COMPLETE);
        String golden = new RunStore(dataSource).checksum(OVERTURE);
        List<String> goldenCategories = liveCategories();

        reset();
        assertThat(run(OSM, false, 0.2)).isEqualTo(Outcome.COMPLETE);
        var crashing = TestIngest.coordinator(failingOnCommit(2), TestIngest.properties(tmp.resolve("raw"), 2, 0.2),
                http(), adapters());
        assertThat(crashing.run(OVERTURE, new RunControl(), false)).isEqualTo(Outcome.ERROR);
        assertThat(run(OVERTURE, false, 0.2)).isEqualTo(Outcome.COMPLETE);

        assertThat(new RunStore(dataSource).checksum(OVERTURE)).isEqualTo(golden);
        assertThat(liveCategories()).containsExactlyInAnyOrderElementsOf(goldenCategories);
        assertThat(jdbc.sql("SELECT count(*) FROM place.place WHERE name = 'New Bakery'").query(Integer.class).single())
                .as("no duplicate place").isEqualTo(1);
    }

    /** The evaluation sample compares each record with other sources' records near it, never with its own source. */
    @Test
    void theEvaluationSampleScoresRecordsAgainstOtherSourcesRecords() throws Exception {
        runAll();
        Path sample = tmp.resolve("eval/match-sample.csv");

        MatchSample.export(dataSource, TestIngest.PRIORITY, 10, 1, sample);

        List<String> lines = Files.readAllLines(sample);
        assertThat(lines.getFirst()).startsWith("case_id,blind,source,source_key");
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("boston_active_food_licenses", "Corner Cafe"));
        assertThat(lines.stream().filter(l -> l.contains(",overture_places,")).count()).as("Overture against OSM")
                .isPositive();
        assertThat(Files.readString(tmp.resolve("eval/match-sample-meta.json"))).contains("\"bands\"", "\"link\"");
    }

    /** What the filters read, refreshed at each completion for the places the run touched. */
    @Test
    void eachPlaceGetsTheFactsTheFiltersRead() throws Exception {
        writeOsm(List.of(
                osmNode(1, CAFE, 0, "amenity", "cafe", "Corner Cafe", "12", "Main Street")
                        .replace("</node>", "<tag k=\"opening_hours\" v=\"Mo-Fr 08:00-18:00\"/></node>"),
                osmNode(2, MARKET, 0, "shop", "supermarket", "Harbor Market", null, null)));
        runAll();

        Map<String, Object> cafe = jdbc.sql("""
                        SELECT active_licence, accepts_snap, hours, hours_source,
                               open_week @> 600 AS open_monday_10am, open_week @> (6 * 1440 + 600) AS open_sunday_10am
                        FROM place.place_facts WHERE place_id = ?
                        """).param(seedPlace).query().singleRow();
        assertThat(cafe).containsEntry("active_licence", true).containsEntry("accepts_snap", false)
                .containsEntry("hours", "Mon–Fri 08:00–18:00; Sat–Sun closed").containsEntry("hours_source", "osm")
                .containsEntry("open_monday_10am", true).containsEntry("open_sunday_10am", false);
        assertThat(jdbc.sql("SELECT active_licence FROM place.place_facts WHERE place_id = ?")
                .param(placeOf(OSM, "node/2")).query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM place.place p
                        WHERE p.retired_at IS NULL AND NOT EXISTS (SELECT 1 FROM place.place_facts f WHERE f.place_id = p.place_id)
                        """).query(Integer.class).single()).as("every live place has facts").isZero();
    }

    /**
     * A large park: OSM's point for it is 300 m from the point inside the city's parcel, far beyond the 100 m radius,
     * but inside the parcel's outline. The city's record must join OSM's place instead of creating a second one.
     */
    @Test
    void aParkFromTheCitysOutlinesJoinsOsmsPlaceForIt() throws Exception {
        double[] south = {-71.0950, 42.3000};
        double north = south[1] + 800 * METRE;
        double east = south[0] + 0.004; // about 330 m
        writeOsm(List.of(osmNode(5, new double[] {south[0] + 0.002, south[1]}, 700, "leisure", "park", "Franklin Park",
                null, null)));
        Files.writeString(openSpaceFile, """
                {"type": "FeatureCollection", "features": [{"type": "Feature",
                  "properties": {"SITE_NAME": "Franklin Park", "TypeLong": "Parks, Playgrounds & Athletic Fields",
                                 "OS_ID": "1001", "ZipCode": "02121"},
                  "geometry": {"type": "Polygon", "coordinates": [[[%1$s, %2$s], [%3$s, %2$s], [%3$s, %4$s], [%1$s, %4$s],
                                                                   [%1$s, %2$s]]]}}]}
                """.formatted(south[0], south[1], east, north));

        assertThat(run(OSM, false, 0.2)).isEqualTo(Outcome.COMPLETE);
        assertThat(run(BostonOpenSpace.ID, false, 0.2)).isEqualTo(Outcome.COMPLETE);

        assertThat(placeOf(BostonOpenSpace.ID, "1001")).isEqualTo(placeOf(OSM, "node/5"));
        assertThat(method(BostonOpenSpace.ID, "1001")).isEqualTo("score");
        assertThat(jdbc.sql("""
                        SELECT ST_GeometryType(outline::geometry) FROM place.source_record
                        WHERE source = ? AND source_key = '1001'
                        """).param(BostonOpenSpace.ID).query(String.class).single()).isEqualTo("ST_MultiPolygon");

        // A later OSM run loads the city's outline into its index: still one place, and the ids stay.
        Map<String, Long> before = links();
        assertThat(run(OSM, true, 0.2)).isEqualTo(Outcome.COMPLETE);
        assertThat(links()).isEqualTo(before);
        assertThat(liveCategories()).filteredOn("park"::equals).hasSize(1); // besides the seed café
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private void runAll() {
        assertThat(run(OSM, false, 0.2)).isEqualTo(Outcome.COMPLETE);
        assertThat(run(OVERTURE, false, 0.2)).isEqualTo(Outcome.COMPLETE);
        assertThat(run(LICENCES, false, 0.2)).isEqualTo(Outcome.COMPLETE);
    }

    private Outcome run(String source, boolean force, double maxRetireShare) {
        // Batches of 2 rows, so every run has several batches committing in parallel.
        return TestIngest.coordinator(dataSource, TestIngest.properties(tmp.resolve("raw"), 2, maxRetireShare), http(),
                adapters()).run(source, new RunControl(), force);
    }

    private SourceAdapter[] adapters() {
        var overture = new OvertureSource(TestData.area()) {
            @Override
            public void fetch(SourceDefinition source, Path target, HttpDownloader http)
                    throws IOException, InterruptedException {
                super.fetch(withUrl(source, overtureFile.toString()), target, http);
            }
        };
        var osm = new OsmSource(TestData.area(), TestData.categories(), "osmium") {
            @Override
            public void fetch(SourceDefinition source, Path target, HttpDownloader http)
                    throws IOException, InterruptedException {
                super.fetch(withUrl(source, "https://example.test/extract.osm"), target, http);
            }
        };
        return new SourceAdapter[] {overture, osm, new BostonActiveFoodLicenses(), new BostonOpenSpace()};
    }

    private HttpDownloader http() {
        return new OfflineHttp() {
            @Override
            public void download(URI uri, Path target) throws IOException {
                Path file = !"data.boston.gov".equals(uri.getHost()) ? osmFile
                        : uri.getPath().endsWith(".geojson") ? openSpaceFile : licenceFile;
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
        };
    }

    private static SourceDefinition withUrl(SourceDefinition s, String url) {
        return new SourceDefinition(s.id(), s.name(), s.enabled(), s.role(),
                new SourceDefinition.Access(s.access().method(), url, s.access().apiKeyEnv()), s.cadence(), s.crs(),
                s.dropFields());
    }

    private Long placeOf(String source, String key) {
        return jdbc.sql("SELECT place_id FROM place.place_source_link WHERE source = ? AND source_key = ?")
                .params(source, key).query(Long.class).optional().orElse(null);
    }

    private String method(String source, String key) {
        return jdbc.sql("SELECT method FROM place.place_source_link WHERE source = ? AND source_key = ?")
                .params(source, key).query(String.class).single();
    }

    private Map<String, Object> place(long placeId) {
        return jdbc.sql("SELECT name, address, category, retired_at IS NOT NULL AS retired FROM place.place WHERE place_id = ?")
                .param(placeId).query().singleRow();
    }

    private List<String> liveCategories() {
        return jdbc.sql("SELECT category FROM place.place WHERE retired_at IS NULL").query(String.class).list();
    }

    private Map<String, Long> links() {
        return jdbc.sql("SELECT source || ':' || source_key AS k, place_id FROM place.place_source_link")
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2)))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private void writeOsm(List<String> nodes) throws IOException {
        Files.writeString(osmFile, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<osm version=\"0.6\" generator=\"test\">\n"
                + String.join("\n", nodes) + "\n</osm>\n");
    }

    private static String osmNode(long id, double[] at, double northMetres, String key, String value, String name,
            String housenumber, String street) {
        var tags = new StringBuilder();
        tags.append("<tag k=\"%s\" v=\"%s\"/><tag k=\"name\" v=\"%s\"/>".formatted(key, value, name));
        if (street != null) {
            tags.append("<tag k=\"addr:housenumber\" v=\"%s\"/><tag k=\"addr:street\" v=\"%s\"/>".formatted(housenumber, street));
        }
        return "  <node id=\"%d\" version=\"1\" lat=\"%s\" lon=\"%s\">%s</node>"
                .formatted(id, at[1] + northMetres * METRE, at[0], tags);
    }

    private void writeOverture(List<String> rows) throws SQLException, IOException {
        Files.deleteIfExists(overtureFile);
        try (var c = DriverManager.getConnection("jdbc:duckdb:"); var s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE place AS SELECT * FROM (VALUES %s) t(id, names, confidence, websites, phones, addresses,
                        sources, operating_status, basic_category, taxonomy, bbox, brand)
                    """.formatted(String.join(",\n", rows)));
            s.execute("COPY place TO '%s' (FORMAT parquet)".formatted(overtureFile));
        }
    }

    private static String overture(String id, String name, String basic, String taxonomy, double confidence, double[] at,
            double northMetres, String address) {
        double lat = at[1] + northMetres * METRE;
        String addresses = address == null ? "[]"
                : "[{'freeform': '%s', 'locality': 'Boston', 'postcode': '02108'}]".formatted(address);
        return ("('%s', {'primary': '%s'}, %s, [], [], %s, [{'dataset': 'meta'}], 'open', '%s', {'primary': '%s'}, "
                + "{'xmin': %s, 'xmax': %s, 'ymin': %s, 'ymax': %s}, {'wikidata': NULL})")
                .formatted(id, name, confidence, addresses, basic, taxonomy, at[0], at[0], lat, lat);
    }

    private static String licence(String business, String dba, String address, String property, double[] at,
            double northMetres) {
        return String.join(",", business, dba, address, "Boston/", "MA", "02108", "Active", "FS", "Eating & Drinking",
                "2020-01-01 00:00:00+00", property, Double.toString(at[1] + northMetres * METRE), Double.toString(at[0]));
    }

    /** A data source whose n-th commit (counted across all connections) rolls back and throws instead. */
    private static DataSource failingOnCommit(int n) {
        var commits = new AtomicInteger();
        return new DelegatingDataSource(dataSource) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection real = super.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                            if (method.getName().equals("commit") && commits.incrementAndGet() == n) {
                                real.rollback();
                                throw new SQLException("injected commit failure", "XX000");
                            }
                            try {
                                return method.invoke(real, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
            }
        };
    }

    private static boolean osmiumAvailable() {
        try {
            return new ProcessBuilder("osmium", "--version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
