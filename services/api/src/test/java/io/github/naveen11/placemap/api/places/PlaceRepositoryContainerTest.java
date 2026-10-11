package io.github.naveen11.placemap.api.places;

import static org.assertj.core.api.Assertions.assertThat;

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
                    .query(String.class)
                    .list();

            assertThat(String.join("\n", plan)).contains("Index Scan using place_geom_live_gist");
        } finally {
            single.destroy();
        }
    }
}
