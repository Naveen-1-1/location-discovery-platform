package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.ingest.IngestProperties;
import io.github.naveen11.placemap.ingest.TestData;
import io.github.naveen11.placemap.ingest.catalog.SourcePriority;
import io.github.naveen11.placemap.ingest.geocode.Geocoding;
import io.github.naveen11.placemap.ingest.geocode.JdbcGeocodeCache;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import io.github.naveen11.placemap.ingest.snapshot.SnapshotStore;
import io.github.naveen11.placemap.ingest.source.SourceAdapter;
import io.github.naveen11.placemap.ingest.source.SourceAdapters;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Builds a run coordinator the way IngestConfig does, over the real registry files, for the container tests. */
final class TestIngest {

    static final SourcePriority PRIORITY = SourcePriority.load(TestData.REPO.resolve("data/source-priority.yaml"));

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
        settings.put("parse-workers", 2);
        settings.put("match-workers", 2);
        settings.put("upsert-workers", 2);
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

    static RunCoordinator coordinator(DataSource dataSource, Path rawDir, int batchSize, HttpDownloader http,
            SourceAdapter... adapters) {
        return coordinator(dataSource, properties(rawDir, batchSize, 0.2), http, adapters);
    }

    /** A geocoder that never finds anything, over the real cache table. */
    static Geocoding noGeocoder(DataSource dataSource) {
        var cache = new JdbcGeocodeCache(dataSource, new DataSourceTransactionManager(dataSource));
        return new Geocoding(query -> List.of(), cache, 4, 100);
    }

    static RunCoordinator coordinator(DataSource dataSource, IngestProperties properties, HttpDownloader http,
            SourceAdapter... adapters) {
        return coordinator(dataSource, properties, http, noGeocoder(dataSource), adapters);
    }

    static RunCoordinator coordinator(DataSource dataSource, IngestProperties properties, HttpDownloader http,
            Geocoding geocoding, SourceAdapter... adapters) {
        Path rawDir = properties.rawDir();
        return new RunCoordinator(properties, TestData.catalog(), new SourceAdapters(List.of(adapters), TestData.catalog()),
                TestData.categories(), PRIORITY, TestData.area(), new SnapshotStore(rawDir, http), new RunStore(dataSource),
                dataSource, new IngestMetrics(new SimpleMeterRegistry()), geocoding);
    }
}
