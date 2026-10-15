package io.github.naveen11.placemap.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.ingest.run.IngestScheduler;
import io.github.naveen11.placemap.ingest.source.SourceAdapter;
import io.github.naveen11.placemap.ingest.source.SourceAdapters;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The application as it ships, in once mode: it starts with its own configuration (Spring Batch on the real
 * migrations, the executors, the import job), imports a pinned snapshot of one source end to end, and keeps the exit
 * code for the JVM. Needs Docker; skipped (not failed) without it.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "placemap.ingest.once=boston_active_food_licenses",
        "placemap.ingest.geocoder.url=https://photon.invalid/api/",
        "placemap.ingest.geocoder.calls-per-second=100"})
class IngestApplicationTest {

    @Container
    static final PostgreSQLContainer POSTGIS = TestDatabase.postgis();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws IOException {
        TestDatabase.migrated(POSTGIS);
        registry.add("spring.datasource.url", () -> TestDatabase.url(POSTGIS));
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
        registry.add("placemap.ingest.pinned-snapshot", TestData.BOSTON_LICENCES::toString);
        registry.add("placemap.ingest.raw-dir", Files.createTempDirectory("ingest-raw")::toString);
    }

    @Autowired
    IngestProperties properties;

    @Autowired
    SourceAdapters adapters;

    @Autowired
    IngestScheduler scheduler;

    @Autowired
    IngestRunner runner;

    @Autowired
    JdbcClient jdbc;

    @Test
    void importsOneSourceInOnceModeWithTheImplementedSourcesAndTheDefaults() {
        assertThat(adapters.all()).extracting(SourceAdapter::sourceId)
                .containsExactlyInAnyOrder("boston_active_food_licenses", "overture_places", "osm_geofabrik_massachusetts",
                        "cambridge_food_establishment_permits", "boston_food_inspections", "cambridge_sanitary_inspections",
                        "bluebikes_station_information", "mbta_gtfs", "massgis_hospitals", "fdic_bank_locations",
                        "afdc_alt_fuel_stations", "boston_open_space", "boston_public_libraries",
                        "boston_bcyf_community_centers", "usda_snap_retailers", "boston_licensing_board_licenses",
                        "boston_park_features", "boston_wicked_free_wifi", "nppes_npi_pharmacies");
        assertThat(properties.batchSize()).isEqualTo(1000);
        assertThat(properties.partitions()).isEqualTo(4);
        assertThat(properties.shutdownGrace()).hasSeconds(20);
        assertThat(scheduler.isRunning()).isTrue();

        assertThat(runner.getExitCode()).isZero();
        assertThat(jdbc.sql("SELECT status FROM ingest.run").query(String.class).single()).isEqualTo("COMPLETE");
        assertThat(jdbc.sql("SELECT status FROM batch.batch_job_execution").query(String.class).single())
                .isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT count(*) FROM place.source_record").query(Integer.class).single()).isEqualTo(40);
    }
}
