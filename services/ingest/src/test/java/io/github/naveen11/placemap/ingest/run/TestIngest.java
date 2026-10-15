package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.ingest.IngestProperties;
import io.github.naveen11.placemap.ingest.TestData;
import io.github.naveen11.placemap.ingest.catalog.CategoryRules;
import io.github.naveen11.placemap.ingest.catalog.CoverageArea;
import io.github.naveen11.placemap.ingest.catalog.SourceCatalog;
import io.github.naveen11.placemap.ingest.catalog.SourcePriority;
import io.github.naveen11.placemap.ingest.geocode.Geocoding;
import io.github.naveen11.placemap.ingest.geocode.JdbcGeocodeCache;
import io.github.naveen11.placemap.ingest.run.RunCoordinator.Outcome;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import io.github.naveen11.placemap.ingest.snapshot.SnapshotStore;
import io.github.naveen11.placemap.ingest.source.SourceAdapter;
import io.github.naveen11.placemap.ingest.source.SourceAdapters;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.batch.autoconfigure.BatchAutoConfiguration;
import org.springframework.boot.batch.autoconfigure.BatchTransactionManager;
import org.springframework.boot.batch.jdbc.autoconfigure.BatchDataSource;
import org.springframework.boot.batch.jdbc.autoconfigure.BatchJdbcAutoConfiguration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Runs imports the way the application does, for the container tests: Boot's Spring Batch and JDBC setup, the import
 * job ({@link ImportJobConfig}) and the coordinator, over the real registry files, with the adapters, downloads and
 * geocoder each test chooses. Each run gets a fresh context, closed when the run ends.
 */
final class TestIngest {

    static final SourcePriority PRIORITY = SourcePriority.load(TestData.REPO.resolve("data/source-priority.yaml"));

    /** One import of a source, as {@link RunCoordinator#run} does it. */
    @FunctionalInterface
    interface Importer {
        Outcome run(String source, boolean force);
    }

    private TestIngest() {
    }

    /** The settings as Spring binds them: every default, plus what the tests choose. */
    static IngestProperties properties(Path rawDir, int batchSize, double maxRetireShare) {
        Path data = TestData.REPO.resolve("data");
        var settings = new LinkedHashMap<String, Object>();
        settings.put("sources-file", data.resolve("sources.yaml"));
        settings.put("categories-file", data.resolve("categories.yaml"));
        settings.put("source-priority-file", data.resolve("source-priority.yaml"));
        settings.put("area-file", data.resolve("area/area.geojson"));
        settings.put("area-config-file", data.resolve("area.yaml"));
        settings.put("raw-dir", rawDir);
        settings.put("batch-size", batchSize);
        settings.put("partitions", 3);
        settings.put("max-concurrent-runs", 1);
        settings.put("max-retire-share", maxRetireShare);
        settings.put("geocoder.url", "https://photon.invalid/api/");
        settings.put("geocoder.calls-per-second", 100);
        settings.put("geocoder.timeout", "5s");
        settings.put("tool.seed", 1);
        var source = new MapConfigurationPropertySource();
        settings.forEach((name, value) -> source.put("placemap.ingest." + name, value.toString()));
        return new Binder(source).bindOrCreate("placemap.ingest", IngestProperties.class);
    }

    static Importer coordinator(DataSource dataSource, Path rawDir, int batchSize, HttpDownloader http,
            SourceAdapter... adapters) {
        return coordinator(dataSource, properties(rawDir, batchSize, 0.2), http, adapters);
    }

    /** A geocoder that never finds anything, over the real cache table. */
    static Geocoding noGeocoder(DataSource dataSource) {
        var cache = new JdbcGeocodeCache(dataSource, new DataSourceTransactionManager(dataSource));
        return new Geocoding(query -> List.of(), cache, 100);
    }

    static Importer coordinator(DataSource dataSource, IngestProperties properties, HttpDownloader http,
            SourceAdapter... adapters) {
        return coordinator(dataSource, properties, http, noGeocoder(dataSource), adapters);
    }

    /**
     * @param dataSource what the import reads and writes through. A test's fault-injecting wrapper
     *     ({@link DelegatingDataSource}) applies to the import only: Spring Batch keeps its own tables through the
     *     database underneath, as if its bookkeeping never failed.
     */
    static Importer coordinator(DataSource dataSource, IngestProperties properties, HttpDownloader http,
            Geocoding geocoding, SourceAdapter... adapters) {
        DataSource batch = dataSource instanceof DelegatingDataSource wrapper
                ? wrapper.getTargetDataSource()
                : dataSource;
        var context = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JdbcTemplateAutoConfiguration.class,
                        BatchAutoConfiguration.class, BatchJdbcAutoConfiguration.class))
                .withPropertyValues("spring.batch.jdbc.table-prefix=batch.BATCH_", "spring.batch.job.enabled=false",
                        "spring.batch.jdbc.isolation-level-for-create=read_committed")
                .withBean("dataSource", DataSource.class, () -> dataSource, bd -> bd.setPrimary(true))
                .withBean("transactionManager", PlatformTransactionManager.class,
                        () -> new JdbcTransactionManager(dataSource), bd -> bd.setPrimary(true))
                .withBean("batchDataSource", DataSource.class, () -> batch,
                        bd -> ((AbstractBeanDefinition) bd).addQualifier(
                                new AutowireCandidateQualifier(BatchDataSource.class)))
                .withBean("batchTransactionManager", PlatformTransactionManager.class,
                        () -> new JdbcTransactionManager(batch),
                        bd -> ((AbstractBeanDefinition) bd).addQualifier(
                                new AutowireCandidateQualifier(BatchTransactionManager.class)))
                .withBean(IngestProperties.class, () -> properties)
                .withBean(SourceCatalog.class, TestData::catalog)
                .withBean(CategoryRules.class, TestData::categories)
                .withBean(SourcePriority.class, () -> PRIORITY)
                .withBean(CoverageArea.class, TestData::area)
                .withBean(SourceAdapters.class, () -> new SourceAdapters(List.of(adapters), TestData.catalog()))
                .withBean(SnapshotStore.class, () -> new SnapshotStore(properties.rawDir(), http))
                .withBean(RunStore.class, () -> new RunStore(dataSource))
                .withBean(IngestMetrics.class, () -> new IngestMetrics(new SimpleMeterRegistry()))
                .withBean(Geocoding.class, () -> geocoding)
                .withBean("ingestPartitions", SimpleAsyncTaskExecutor.class, () -> virtualThreads("test-batch-"))
                .withBean("geocodeCalls", SimpleAsyncTaskExecutor.class, () -> virtualThreads("test-geocode-"))
                .withUserConfiguration(ImportJobConfig.class);
        return (source, force) -> {
            var outcome = new AtomicReference<Outcome>();
            context.run(started -> outcome.set(started.getBean(RunCoordinator.class).run(source, force)));
            return outcome.get();
        };
    }

    private static SimpleAsyncTaskExecutor virtualThreads(String prefix) {
        var executor = new SimpleAsyncTaskExecutor(prefix);
        executor.setVirtualThreads(true);
        return executor;
    }
}
