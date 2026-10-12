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
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;

/** Builds a run coordinator the way IngestConfig does, over the real registry files, for the container tests. */
final class TestIngest {

    static final SourcePriority PRIORITY = SourcePriority.load(TestData.REPO.resolve("data/source-priority.yaml"));

    private TestIngest() {
    }

    static IngestProperties properties(Path rawDir, int batchSize, double maxRetireShare) {
        Path data = TestData.REPO.resolve("data");
        return new IngestProperties(data.resolve("sources.yaml"), data.resolve("categories.yaml"),
                data.resolve("source-priority.yaml"), data.resolve("area/area.geojson"), data.resolve("area.yaml"), rawDir,
                batchSize, 2, 2, 2, 1, Duration.ofMinutes(1), Duration.ofSeconds(20), Duration.ofHours(1), 3, 0.02,
                maxRetireShare, null, false, null,
                new IngestProperties.Geocoder(URI.create("https://photon.invalid/api/"), 4, 100, Duration.ofSeconds(5)),
                new IngestProperties.Tool(null, null, null, 100, 1, null, null, null));
    }

    static RunCoordinator coordinator(DataSource dataSource, Path rawDir, int batchSize, HttpDownloader http,
            SourceAdapter... adapters) {
        return coordinator(dataSource, properties(rawDir, batchSize, 0.2), http, adapters);
    }

    /** A geocoder that never finds anything, over the real cache table. */
    static Geocoding noGeocoder(DataSource dataSource) {
        return new Geocoding(query -> List.of(), new JdbcGeocodeCache(dataSource), 4, 100);
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
