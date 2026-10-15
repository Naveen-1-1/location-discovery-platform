package io.github.naveen11.placemap.ingest.source;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.ingest.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Phase 2 golden test: EPSG:2249 → WGS84 with proj4j must agree with PostGIS (PROJ) within 0.5 m, on a grid covering
 * the four towns. Needs Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReprojectionGoldenContainerTest {


    @Container
    static final PostgreSQLContainer POSTGIS = TestDatabase.postgis();

    @Test
    void proj4jAgreesWithPostgisWithinHalfAMetreAcrossTheArea() {
        var jdbc = JdbcClient.create(new DriverManagerDataSource(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(),
                POSTGIS.getPassword()));
        jdbc.sql("CREATE EXTENSION IF NOT EXISTS postgis").update();
        var reprojector = new Reprojector();
        double worst = 0;
        int points = 0;
        // State plane feet: x 735,000–800,000 (Brookline/West Roxbury to East Boston), y 2,905,000–2,985,000.
        for (double x = 735_000; x <= 800_000; x += 13_000) {
            for (double y = 2_905_000; y <= 2_985_000; y += 16_000) {
                double[] ours = reprojector.toWgs84("EPSG:2249", x, y);
                double metres = jdbc.sql("""
                                SELECT ST_Distance(
                                           ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 2249), 4326)::geography,
                                           ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
                                """)
                        .params(x, y, ours[0], ours[1])
                        .query(Double.class)
                        .single();
                worst = Math.max(worst, metres);
                points++;
            }
        }

        assertThat(points).isEqualTo(36);
        assertThat(worst).as("largest difference in metres").isLessThan(0.5);
    }
}
