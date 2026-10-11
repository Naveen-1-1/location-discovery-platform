package io.github.naveen11.placemap.ingest;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code placemap.ingest.*}. Paths are relative to the working directory, which is the repository root when started
 * through tools/dev.sh.
 *
 * @param parseWorkers 0 means half the CPU cores, as the plan sizes the parse stage
 * @param once run (or resume) this one source, then exit; empty means service mode with the scheduler
 * @param force import even if the download is identical to the last successful one
 */
@ConfigurationProperties("placemap.ingest")
public record IngestProperties(
        @DefaultValue("data/sources.yaml") Path sourcesFile,
        @DefaultValue("data/categories.yaml") Path categoriesFile,
        @DefaultValue("data/area/area.geojson") Path areaFile,
        @DefaultValue("data/raw") Path rawDir,
        @DefaultValue("1000") int batchSize,
        @DefaultValue("0") int parseWorkers,
        @DefaultValue("4") int upsertWorkers,
        @DefaultValue("2") int maxConcurrentRuns,
        @DefaultValue("1m") Duration tick,
        @DefaultValue("20s") Duration shutdownGrace,
        @DefaultValue("1h") Duration retryFailedAfter,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("0.02") double maxRejectShare,
        @DefaultValue("0.2") double maxRetireShare,
        String once,
        @DefaultValue("false") boolean force) {

    public int parseWorkerCount() {
        return parseWorkers > 0 ? parseWorkers : Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }

    public boolean onceMode() {
        return once != null && !once.isBlank();
    }
}
