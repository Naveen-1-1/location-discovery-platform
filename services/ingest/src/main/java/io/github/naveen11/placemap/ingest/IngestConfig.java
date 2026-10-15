package io.github.naveen11.placemap.ingest;

import io.github.naveen11.placemap.ingest.catalog.AreaPostcodes;
import io.github.naveen11.placemap.ingest.catalog.CategoryRules;
import io.github.naveen11.placemap.ingest.catalog.CoverageArea;
import io.github.naveen11.placemap.ingest.catalog.SourceCatalog;
import io.github.naveen11.placemap.ingest.catalog.SourcePriority;
import io.github.naveen11.placemap.ingest.geocode.Geocoding;
import io.github.naveen11.placemap.ingest.geocode.JdbcGeocodeCache;
import io.github.naveen11.placemap.ingest.geocode.PhotonGeocoder;
import io.github.naveen11.placemap.ingest.run.IngestMetrics;
import io.github.naveen11.placemap.ingest.run.IngestScheduler;
import io.github.naveen11.placemap.ingest.run.RunCoordinator;
import io.github.naveen11.placemap.ingest.run.RunStore;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import io.github.naveen11.placemap.ingest.snapshot.SnapshotStore;
import io.github.naveen11.placemap.ingest.source.SourceAdapter;
import io.github.naveen11.placemap.ingest.source.SourceAdapters;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

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
    AreaPostcodes areaPostcodes(IngestProperties properties) {
        return AreaPostcodes.load(properties.areaConfigFile());
    }

    /** Shared by every run, so the call limits hold across runs. */
    @Bean
    Geocoding geocoding(IngestProperties properties, CoverageArea area, DataSource dataSource,
            PlatformTransactionManager transactions) {
        IngestProperties.Geocoder settings = properties.geocoder();
        var client = HttpClient.newBuilder().connectTimeout(settings.timeout()).build();
        return new Geocoding(new PhotonGeocoder(settings.url(), area.bounds(), settings.timeout(), client),
                new JdbcGeocodeCache(dataSource, transactions), settings.maxConcurrentCalls(),
                settings.callsPerSecond());
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
            DataSource dataSource, IngestMetrics metrics, Geocoding geocoding) {
        return new RunCoordinator(properties, catalog, adapters, categories, priority, area, snapshots, runs,
                dataSource, metrics, geocoding);
    }

    @Bean
    IngestScheduler ingestScheduler(IngestProperties properties, SourceCatalog catalog, SourceAdapters adapters,
            RunCoordinator coordinator, RunStore runs) {
        return new IngestScheduler(properties, catalog, adapters, coordinator, runs);
    }
}
