package io.github.naveen11.placemap.ingest;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code placemap.ingest.*}. Paths are relative to the working directory, which is the repository root when started
 * through tools/dev.sh.
 *
 * @param parseWorkers 0 means half the CPU cores, as the plan sizes the parse stage
 * @param matchWorkers 0 means half the CPU cores, as the plan sizes the match stage
 * @param once run (or resume) this one source, then exit; empty means service mode with the scheduler
 * @param force import even if the download is identical to the last successful one
 * @param pinnedSnapshot once mode only: import this local snapshot file instead of downloading (crash tests and
 *     benchmarks need the same input every time)
 * @param areaConfigFile the four towns' settings, such as their ZIP codes (for sources searched by ZIP)
 */
@ConfigurationProperties("placemap.ingest")
public record IngestProperties(
        @DefaultValue("data/sources.yaml") Path sourcesFile,
        @DefaultValue("data/categories.yaml") Path categoriesFile,
        @DefaultValue("data/source-priority.yaml") Path sourcePriorityFile,
        @DefaultValue("data/area/area.geojson") Path areaFile,
        @DefaultValue("data/area.yaml") Path areaConfigFile,
        @DefaultValue("data/raw") Path rawDir,
        @DefaultValue("1000") int batchSize,
        @DefaultValue("0") int parseWorkers,
        @DefaultValue("0") int matchWorkers,
        @DefaultValue("4") int upsertWorkers,
        @DefaultValue("2") int maxConcurrentRuns,
        @DefaultValue("1m") Duration tick,
        @DefaultValue("20s") Duration shutdownGrace,
        @DefaultValue("1h") Duration retryFailedAfter,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("0.02") double maxRejectShare,
        @DefaultValue("0.2") double maxRetireShare,
        String once,
        @DefaultValue("false") boolean force,
        Path pinnedSnapshot,
        @DefaultValue Geocoder geocoder,
        @DefaultValue Tool tool) {

    /**
     * {@code placemap.ingest.tool.*}: run one tool instead of importing, then exit (tools/dev.sh match-sample,
     * match-evaluate, release, release-check).
     *
     * @param name {@code match-sample}, {@code match-evaluate}, {@code release} or {@code release-check}; empty for
     *     the importer
     * @param ownerLabels match-evaluate: the owner's blind labels, compared with the sample's
     * @param releaseDir release: where each release's dated folder is built (ADR 0044)
     * @param licencesDir release: the licence texts it ships
     * @param releaseFolder release-check: the folder to check; empty means the newest under releaseDir
     */
    public record Tool(
            String name,
            @DefaultValue("data/eval/match-sample.csv") Path sample,
            @DefaultValue("data/eval/match-labels-owner.csv") Path ownerLabels,
            @DefaultValue("100") int perBand,
            @DefaultValue("20261011") long seed,
            @DefaultValue("data/release") Path releaseDir,
            @DefaultValue("data/licences") Path licencesDir,
            Path releaseFolder) {
    }

    /**
     * {@code placemap.ingest.geocoder.*}: Photon (ADR 0008), public during development (ADR 0033).
     *
     * @param maxConcurrentCalls calls in flight across all runs (the plan's Semaphore(4))
     * @param callsPerSecond public Photon asks for fair use; our own instance can take more
     */
    public record Geocoder(
            @DefaultValue("https://photon.komoot.io/api/") URI url,
            @DefaultValue("4") int maxConcurrentCalls,
            @DefaultValue("1") double callsPerSecond,
            @DefaultValue("10s") Duration timeout) {
    }

    public IngestProperties {
        if (pinnedSnapshot != null && (once == null || once.isBlank())) {
            throw new IllegalArgumentException("placemap.ingest.pinned-snapshot needs once mode: the scheduler would "
                    + "import the same file for every source");
        }
    }

    public int parseWorkerCount() {
        return parseWorkers > 0 ? parseWorkers : halfTheCores();
    }

    public int matchWorkerCount() {
        return matchWorkers > 0 ? matchWorkers : halfTheCores();
    }

    private static int halfTheCores() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }

    public boolean onceMode() {
        return once != null && !once.isBlank();
    }

    public boolean toolMode() {
        return tool != null && tool.name() != null && !tool.name().isBlank();
    }
}
