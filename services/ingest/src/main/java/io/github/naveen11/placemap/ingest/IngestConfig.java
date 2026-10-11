package io.github.naveen11.placemap.ingest;

import io.github.naveen11.placemap.ingest.catalog.CategoryRules;
import io.github.naveen11.placemap.ingest.catalog.CoverageArea;
import io.github.naveen11.placemap.ingest.catalog.SourceCatalog;
import io.github.naveen11.placemap.ingest.catalog.SourcePriority;
import io.github.naveen11.placemap.ingest.run.IngestMetrics;
import io.github.naveen11.placemap.ingest.run.IngestScheduler;
import io.github.naveen11.placemap.ingest.run.RunCoordinator;
import io.github.naveen11.placemap.ingest.run.RunStore;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import io.github.naveen11.placemap.ingest.snapshot.SnapshotStore;
import io.github.naveen11.placemap.ingest.source.SourceAdapter;
import io.github.naveen11.placemap.ingest.source.SourceAdapters;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the importer. The source adapters are {@code @Component}s; everything else is built here. */
@Configuration(proxyBeanMethods = false)
class IngestConfig {

    @Bean
    SourceCatalog sourceCatalog(IngestProperties properties) {
        return SourceCatalog.load(properties.sourcesFile());
    }

    @Bean
    CategoryRules categoryRules(IngestProperties properties) {
        return CategoryRules.load(properties.categoriesFile());
    }

    @Bean
    SourcePriority sourcePriority(IngestProperties properties) {
        return SourcePriority.load(properties.sourcePriorityFile());
    }

    @Bean
    CoverageArea coverageArea(IngestProperties properties) {
        return CoverageArea.load(properties.areaFile());
    }

    @Bean
    SourceAdapters sourceAdapters(List<SourceAdapter> adapters, SourceCatalog catalog) {
        return new SourceAdapters(adapters, catalog);
    }

    @Bean
    SnapshotStore snapshotStore(IngestProperties properties) {
        return new SnapshotStore(properties.rawDir(), new HttpDownloader(), properties.pinnedSnapshot());
    }

    @Bean
    RunStore runStore(DataSource dataSource) {
        return new RunStore(dataSource);
    }

    @Bean
    IngestMetrics ingestMetrics(MeterRegistry registry) {
        return new IngestMetrics(registry);
    }

    @Bean
    RunCoordinator runCoordinator(IngestProperties properties, SourceCatalog catalog, SourceAdapters adapters,
            CategoryRules categories, SourcePriority priority, CoverageArea area, SnapshotStore snapshots, RunStore runs,
            DataSource dataSource, IngestMetrics metrics) {
        return new RunCoordinator(properties, catalog, adapters, categories, priority, area, snapshots, runs,
                dataSource, metrics);
    }

    @Bean
    IngestScheduler ingestScheduler(IngestProperties properties, SourceCatalog catalog, SourceAdapters adapters,
            RunCoordinator coordinator, RunStore runs) {
        return new IngestScheduler(properties, catalog, adapters, coordinator, runs);
    }
}
