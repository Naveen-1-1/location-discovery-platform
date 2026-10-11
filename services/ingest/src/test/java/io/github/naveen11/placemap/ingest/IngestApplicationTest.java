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
        assertThat(adapters.all()).extracting(SourceAdapter::sourceId).containsExactly("boston_active_food_licenses");
        assertThat(properties.batchSize()).isEqualTo(1000);
        assertThat(properties.upsertWorkers()).isEqualTo(4);
        assertThat(properties.shutdownGrace()).hasSeconds(20);
        assertThat(properties.parseWorkerCount()).isPositive();
        assertThat(scheduler.isRunning()).isTrue();
    }
}
