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
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires the importer. The source adapters are {@code @Component}s and the import job is in {@code ImportJobConfig};
 * everything else is built here, including the threads, each from a Spring executor named for what it runs:
 *
 * <ul>
 *   <li>{@code ingest-scheduler}: the one platform thread that ticks;
 *   <li>{@code ingest-run-}: one virtual thread per run, at most {@code max-concurrent-runs};
 *   <li>{@code ingest-batch-}: one virtual thread per partition of a run's batches;
 *   <li>{@code ingest-geocode-}: one virtual thread per geocoder call, at most {@code geocoder.max-concurrent-calls};
 *   <li>{@code ingest-native-}: platform threads for native code (DuckDB), which would pin a virtual thread's carrier.
 * </ul>
 */
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

    /** Shared by every run, so the rate limit holds across runs. */
    @Bean
    Geocoding geocoding(IngestProperties properties, CoverageArea area, DataSource dataSource,
            PlatformTransactionManager transactions) {
        IngestProperties.Geocoder settings = properties.geocoder();
        var client = HttpClient.newBuilder().connectTimeout(settings.timeout()).build();
        return new Geocoding(new PhotonGeocoder(settings.url(), area.bounds(), settings.timeout(), client),
                new JdbcGeocodeCache(dataSource, transactions), settings.callsPerSecond());
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
    IngestScheduler ingestScheduler(IngestProperties properties, SourceCatalog catalog, SourceAdapters adapters,
            RunCoordinator coordinator, RunStore runs, @Qualifier("ingestTicks") TaskScheduler ticks,
            @Qualifier("ingestRuns") AsyncTaskExecutor runThreads) {
        return new IngestScheduler(properties, catalog, adapters, coordinator, runs, ticks, runThreads);
    }

    @Bean(defaultCandidate = false)
    @Qualifier("ingestTicks")
    ThreadPoolTaskScheduler ingestTicks(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.poolSize(1).threadNamePrefix("ingest-scheduler-").build();
    }

    /** Refuses a run beyond the limit instead of queueing it: the scheduler tries the source again next tick. */
    @Bean(defaultCandidate = false)
    @Qualifier("ingestRuns")
    SimpleAsyncTaskExecutor ingestRuns(SimpleAsyncTaskExecutorBuilder builder, IngestProperties properties) {
        return builder.virtualThreads(true).threadNamePrefix("ingest-run-")
                .concurrencyLimit(properties.maxConcurrentRuns()).rejectTasksWhenLimitReached(true).build();
    }

    /** On close (after the shutdown grace), interrupts partitions still running; their batch in flight rolls back. */
    @Bean(defaultCandidate = false)
    @Qualifier("ingestPartitions")
    SimpleAsyncTaskExecutor ingestPartitions(SimpleAsyncTaskExecutorBuilder builder) {
        return builder.virtualThreads(true).threadNamePrefix("ingest-batch-").cancelRemainingTasksOnClose(true)
                .taskTerminationTimeout(Duration.ofSeconds(5)).build();
    }

    /** Caps the geocoder calls in flight across all runs: a submit waits while the limit is reached. */
    @Bean(defaultCandidate = false)
    @Qualifier("geocodeCalls")
    SimpleAsyncTaskExecutor geocodeCalls(SimpleAsyncTaskExecutorBuilder builder, IngestProperties properties) {
        return builder.virtualThreads(true).threadNamePrefix("ingest-geocode-")
                .concurrencyLimit(properties.geocoder().maxConcurrentCalls()).build();
    }

    @Bean(defaultCandidate = false)
    @Qualifier("nativeCalls")
    SimpleAsyncTaskExecutor nativeCalls(SimpleAsyncTaskExecutorBuilder builder) {
        return builder.virtualThreads(false).threadNamePrefix("ingest-native-").build();
    }
}
