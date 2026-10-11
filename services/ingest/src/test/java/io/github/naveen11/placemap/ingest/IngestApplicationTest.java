package io.github.naveen11.placemap.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.ingest.run.IngestScheduler;
import io.github.naveen11.placemap.ingest.source.SourceAdapter;
import io.github.naveen11.placemap.ingest.source.SourceAdapters;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The application context starts and binds its configuration. Needs no database: connections are opened lazily, and
 * once mode keeps the scheduler from ticking.
 */
@SpringBootTest(properties = {
        "PGHOST=localhost:1", "PGDATABASE=none", "PGUSER=none", "PGPASSWORD=none",
        "placemap.ingest.once=boston_active_food_licenses"})
class IngestApplicationTest {

    @Autowired
    IngestProperties properties;

    @Autowired
    SourceAdapters adapters;

    @Autowired
    IngestScheduler scheduler;

    @Test
    void startsWithTheImplementedSourcesAndTheDefaults() {
        assertThat(adapters.all()).extracting(SourceAdapter::sourceId)
                .containsExactlyInAnyOrder("boston_active_food_licenses", "overture_places", "osm_geofabrik_massachusetts",
                        "cambridge_food_establishment_permits", "boston_food_inspections", "cambridge_sanitary_inspections",
                        "bluebikes_station_information", "mbta_gtfs", "massgis_hospitals", "fdic_bank_locations",
                        "afdc_alt_fuel_stations", "boston_open_space", "boston_public_libraries",
                        "boston_bcyf_community_centers", "usda_snap_retailers", "boston_licensing_board_licenses",
                        "boston_park_features", "boston_wicked_free_wifi", "nppes_npi_pharmacies");
        assertThat(properties.batchSize()).isEqualTo(1000);
        assertThat(properties.upsertWorkers()).isEqualTo(4);
        assertThat(properties.shutdownGrace()).hasSeconds(20);
        assertThat(properties.parseWorkerCount()).isPositive();
        assertThat(scheduler.isRunning()).isTrue();
    }
}
