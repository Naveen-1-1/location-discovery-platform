package io.github.naveen11.placemap.api.places;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.api.live.LiveSources;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the real migrations on our PostGIS image and checks the nearby query against a brute-force answer.
 * Needs Docker; skipped (not failed) where Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class PlaceRepositoryContainerTest {

    private static final Path POSTGIS_IMAGE_DIR = Path.of("../../infra/docker/postgis").toAbsolutePath().normalize();
    private static final double BOSTON_COMMON_LAT = 42.3550;
    private static final double BOSTON_COMMON_LON = -71.0656;
    private static final int RADIUS_M = 1500;

    @Container
    static final GenericContainer<?> POSTGIS = new GenericContainer<>(
                    new ImageFromDockerfile("placemap/postgis-test", false).withFileFromPath(".", POSTGIS_IMAGE_DIR))
            .withEnv("POSTGRES_DB", "placemap")
            .withEnv("POSTGRES_PASSWORD", "test")
            .withExposedPorts(5432)
            // The entrypoint starts Postgres twice (init, then for real), so wait for the second "ready".
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));

    private static JdbcClient jdbc;
    private static PlaceRepository repository;

    @BeforeAll
    static void migrateAndSeed() {
        var url = "jdbc:postgresql://%s:%d/placemap".formatted(POSTGIS.getHost(), POSTGIS.getMappedPort(5432));
        var dataSource = new DriverManagerDataSource(url, "postgres", "test");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        repository = new PlaceRepository(jdbc);

        // 600 places scattered up to ~3 km around Boston Common; fixed seed keeps failures reproducible.
        var random = new Random(42);
        var categories = List.of("food", "gym", "park");
        for (int i = 0; i < 600; i++) {
            double lat = BOSTON_COMMON_LAT + (random.nextDouble() - 0.5) * 0.054;
            double lon = BOSTON_COMMON_LON + (random.nextDouble() - 0.5) * 0.072;
            jdbc.sql("""
                    INSERT INTO place.place (name, category, geom, retired_at)
                    VALUES (:name, :category, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
                            CASE WHEN :retired THEN now() END)
                    """)
                    .param("name", "place-" + i)
                    .param("category", categories.get(i % categories.size()))
                    .param("lon", lon)
                    .param("lat", lat)
                    .param("retired", i % 10 == 0)
                    .update();
        }
    }

    @Test
    void returnsExactlyThePlacesWithinTheRadiusNearestFirst() {
        var result = repository.nearby(BOSTON_COMMON_LAT, BOSTON_COMMON_LON, RADIUS_M, List.of(), 200);

        var bruteForce = jdbc.sql("""
                        SELECT place_id FROM place.place
                        WHERE retired_at IS NULL
                          AND ST_Distance(geom, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) <= :radius
                        """)
                .param("lon", BOSTON_COMMON_LON)
                .param("lat", BOSTON_COMMON_LAT)
                .param("radius", RADIUS_M)
                .query(Long.class)
                .list();

        assertThat(bruteForce).hasSizeBetween(20, 200); // the fixture really exercises the query
        assertThat(result).extracting(NearbyPlace::id).containsExactlyInAnyOrderElementsOf(bruteForce);
        // The displayed distance must never decrease down the list (the KNN index alone orders by sphere distance,
        // which disagreed with the displayed spheroid distance by 2 m before the outer re-sort was added).
        assertThat(result).extracting(NearbyPlace::distanceMeters).isSorted();
    }

    @Test
    void filtersByCategoryAndHonoursTheLimit() {
        var result = repository.nearby(BOSTON_COMMON_LAT, BOSTON_COMMON_LON, 5000, List.of("gym"), 25);

        assertThat(result).hasSize(25).allSatisfy(p -> assertThat(p.category()).isEqualTo("gym"));
    }

    @Test
    void neverReturnsRetiredPlaces() {
        var retiredIds = jdbc.sql("SELECT place_id FROM place.place WHERE retired_at IS NOT NULL").query(Long.class).list();

        var result = repository.nearby(BOSTON_COMMON_LAT, BOSTON_COMMON_LON, 5000, List.of(), 200);

        assertThat(retiredIds).isNotEmpty();
        assertThat(result).extracting(NearbyPlace::id).doesNotContainAnyElementsOf(retiredIds);
    }

    @Test
    void locatesLivePlacesButNotRetiredOnes() {
        long live = jdbc.sql("SELECT min(place_id) FROM place.place WHERE retired_at IS NULL").query(Long.class).single();
        long retired = jdbc.sql("SELECT min(place_id) FROM place.place WHERE retired_at IS NOT NULL")
                .query(Long.class).single();

        assertThat(repository.location(live)).hasValueSatisfying(place -> {
            assertThat(place.id()).isEqualTo(live);
            assertThat(place.lat()).isBetween(42.0, 43.0);
            assertThat(place.lon()).isBetween(-72.0, -70.0);
        });
        assertThat(repository.location(retired)).isEmpty();
        assertThat(repository.location(-1)).isEmpty();
    }

    /**
     * Guards the query shape, not the planner's cost choice: with sequential scans disabled, the partial GiST index
     * must be able to serve both the radius filter and the KNN ordering. Rewriting the query (e.g. moving the origin
     * into a CTE, or dropping "retired_at IS NULL") silently loses the index; this test catches that.
     */
    @Test
    void nearbyQueryShapeCanUseTheSpatialIndex() {
        var url = "jdbc:postgresql://%s:%d/placemap".formatted(POSTGIS.getHost(), POSTGIS.getMappedPort(5432));
        var single = new SingleConnectionDataSource(url, "postgres", "test", true);
        try {
            var session = JdbcClient.create(single);
            session.sql("SET enable_seqscan = off").update();
            var plan = session.sql("EXPLAIN " + PlaceRepository.nearbySql(false))
                    .param("lat", BOSTON_COMMON_LAT)
                    .param("lon", BOSTON_COMMON_LON)
                    .param("radius", RADIUS_M)
                    .param("limit", 50)
                    .param("liveSources", List.copyOf(LiveSources.TOPICS.keySet()))
                    .query(String.class)
                    .list();

            assertThat(String.join("\n", plan)).contains("Index Scan using place_geom_live_gist");
        } finally {
            single.destroy();
        }
    }

    @Test
    void aBluebikesDockCarriesItsLiveKey() {
        // A dock out at Jamaica Pond, far from the 600 places above, with its Bluebikes link.
        long dock = jdbc.sql("""
                        INSERT INTO place.place (name, category, geom)
                        VALUES ('Jamaica Pond dock', 'bike_share', ST_SetSRID(ST_MakePoint(-71.1162, 42.3165), 4326)::geography)
                        RETURNING place_id
                        """).query(Long.class).single();
        jdbc.sql("INSERT INTO place.place_source_link (source, source_key, place_id, method) VALUES (:s, :k, :p, 'created')")
                .param("s", "bluebikes_station_information").param("k", "st-pond").param("p", dock).update();
        jdbc.sql("INSERT INTO place.place_source_link (source, source_key, place_id, method) VALUES (:s, :k, :p, 'created')")
                .param("s", "osm_geofabrik_massachusetts").param("k", "node/1").param("p", dock).update();

        var result = repository.nearby(42.3165, -71.1162, 200, List.of(), 10);

        assertThat(result).singleElement().satisfies(place -> {
            assertThat(place.id()).isEqualTo(dock);
            assertThat(place.liveKey()).isEqualTo("bikes:st-pond");
        });
        assertThat(repository.nearby(BOSTON_COMMON_LAT, BOSTON_COMMON_LON, RADIUS_M, List.of(), 200))
                .allSatisfy(place -> assertThat(place.liveKey()).isNull());
    }

    @Test
    void filtersKeepOnlyPlacesWhoseFactsPassAndDetailsShowThem() {
        // Two cafes at Kenmore, far from the places above: one open on weekday mornings and SNAP-registered.
        long open = jdbc.sql("""
                        INSERT INTO place.place (name, category, geom, address)
                        VALUES ('Morning Cafe', 'food', ST_SetSRID(ST_MakePoint(-71.0950, 42.3488), 4326)::geography,
                                '1 Beacon St')
                        RETURNING place_id
                        """).query(Long.class).single();
        long other = jdbc.sql("""
                        INSERT INTO place.place (name, category, geom)
                        VALUES ('Evening Bar', 'food', ST_SetSRID(ST_MakePoint(-71.0951, 42.3489), 4326)::geography)
                        RETURNING place_id
                        """).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO place.place_facts (place_id, accepts_snap, active_licence, inspection, park_features, hours,
                                               hours_source, open_week)
                VALUES (:open, true, true, 'passed', 'Playground:1', 'Mon–Fri 07:00–11:00; Sat–Sun closed', 'osm',
                        '{[420,660),[1860,2100),[3300,3540),[4740,4980),[6180,6420)}'),
                       (:other, false, true, 'failed', null, null, null, null)
                """).param("open", open).param("other", other).update();

        assertThat(repository.nearby(42.3488, -71.0950, 100, List.of(), PlaceFilters.NONE, 10)).hasSize(2);
        assertThat(repository.nearby(42.3488, -71.0950, 100, List.of(), new PlaceFilters(false, true, false, null), 10))
                .hasSize(2);
        assertThat(repository.nearby(42.3488, -71.0950, 100, List.of(), new PlaceFilters(true, false, true, 480), 10))
                .singleElement().satisfies(p -> {
                    assertThat(p.id()).isEqualTo(open);
                    assertThat(p.facts().acceptsSnap()).isTrue();
                    assertThat(p.facts().week().openAt(480)).isTrue();
                });
        assertThat(repository.nearby(42.3488, -71.0950, 100, List.of(), new PlaceFilters(false, false, false, 720), 10))
                .as("Monday noon: closed, and the bar's hours are unknown").isEmpty();
        assertThat(repository.unknownHours(42.3488, -71.0950, 100, List.of(), new PlaceFilters(false, false, false, 720)))
                .as("the bar, left out for unknown hours").isEqualTo(1);
        assertThat(repository.unknownHours(42.3488, -71.0950, 100, List.of(), new PlaceFilters(true, false, false, 720)))
                .as("and not counted when another filter would drop it anyway").isZero();

        PlaceDetails details = repository.details(open, 480).orElseThrow();
        assertThat(details.facts().hours()).isEqualTo(new PlaceDetails.Hours("Mon–Fri 07:00–11:00; Sat–Sun closed",
                "osm", true));
        assertThat(details.facts().parkFeatures()).containsExactly(new PlaceDetails.Feature("Playground", 1));
        assertThat(repository.details(other, 480).orElseThrow().facts().hours()).isNull();
    }

    @Test
    void filteredQueriesCanStillUseTheSpatialIndex() {
        var url = "jdbc:postgresql://%s:%d/placemap".formatted(POSTGIS.getHost(), POSTGIS.getMappedPort(5432));
        var single = new SingleConnectionDataSource(url, "postgres", "test", true);
        try {
            var session = JdbcClient.create(single);
            session.sql("SET enable_seqscan = off").update();
            var plan = session.sql("EXPLAIN " + PlaceRepository.nearbySql(true, new PlaceFilters(true, true, true, 600)))
                    .param("lat", BOSTON_COMMON_LAT)
                    .param("lon", BOSTON_COMMON_LON)
                    .param("radius", RADIUS_M)
                    .param("limit", 50)
                    .param("categories", List.of("food"))
                    .param("openAt", 600)
                    .param("liveSources", List.copyOf(LiveSources.TOPICS.keySet()))
                    .query(String.class)
                    .list();

            assertThat(String.join("\n", plan)).contains("Index Scan using place_geom_live_gist");
        } finally {
            single.destroy();
        }
    }
}
