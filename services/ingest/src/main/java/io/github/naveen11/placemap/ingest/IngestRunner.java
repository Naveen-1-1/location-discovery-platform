package io.github.naveen11.placemap.ingest;

import io.github.naveen11.placemap.ingest.catalog.CoverageArea;
import io.github.naveen11.placemap.ingest.catalog.SourcePriority;
import io.github.naveen11.placemap.ingest.match.MatchEvaluation;
import io.github.naveen11.placemap.ingest.match.MatchSample;
import io.github.naveen11.placemap.ingest.release.ReleaseCheck;
import io.github.naveen11.placemap.ingest.release.ReleaseExport;
import io.github.naveen11.placemap.ingest.release.SourceTerms;
import io.github.naveen11.placemap.ingest.run.IngestScheduler;
import io.github.naveen11.placemap.ingest.run.RunCoordinator.Outcome;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * Once mode and tool mode ({@link IngestApplication}): runs the one source or the one tool as the application starts,
 * and keeps the exit code for the JVM. Does nothing in service mode, where the scheduler runs.
 */
@Component
class IngestRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final ZoneId BOSTON = ZoneId.of("America/New_York");

    private final IngestProperties properties;
    private final IngestScheduler scheduler;
    private final DataSource dataSource;
    private final SourcePriority priority;
    private final CoverageArea area;
    private int exitCode;

    IngestRunner(IngestProperties properties, IngestScheduler scheduler, DataSource dataSource,
            SourcePriority priority, CoverageArea area) {
        this.properties = properties;
        this.scheduler = scheduler;
        this.dataSource = dataSource;
        this.priority = priority;
        this.area = area;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.toolMode()) {
            exitCode = runTool(properties, dataSource, priority, area);
        } else if (properties.onceMode()) {
            Outcome outcome = scheduler.runOnce(properties.once());
            exitCode = outcome == Outcome.COMPLETE || outcome == Outcome.UNCHANGED ? 0 : 1;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private static int runTool(IngestProperties properties, DataSource dataSource, SourcePriority priority,
            CoverageArea area) {
        IngestProperties.Tool tool = properties.tool();
        try {
            switch (tool.name()) {
                case "match-sample" -> {
                    MatchSample.export(dataSource, priority, tool.perBand(), tool.seed(), tool.sample());
                    System.out.println("wrote " + tool.sample() + " and its meta file");
                }
                case "match-evaluate" -> System.out.print(MatchEvaluation.report(tool.sample(), tool.ownerLabels()));
                case "release" -> {
                    var summary = ReleaseExport.build(dataSource, SourceTerms.load(properties.sourcesFile()), priority,
                            area, tool.licencesDir(), tool.releaseDir(), LocalDate.now(BOSTON));
                    System.out.printf("release built in %s: %,d places from %,d source records; checks passed%n",
                            summary.folder(), summary.places(), summary.records());
                    summary.byCategory().forEach((category, n) -> System.out.printf("  %-17s %,6d%n", category, n));
                }
                case "release-check" -> {
                    Path folder = tool.releaseFolder() != null ? tool.releaseFolder()
                            : ReleaseCheck.newest(tool.releaseDir()).orElse(null);
                    if (folder == null) {
                        System.out.println("RELEASE CHECK SKIPPED: no release under " + tool.releaseDir());
                        return 0;
                    }
                    List<String> problems = ReleaseCheck.check(folder, SourceTerms.load(properties.sourcesFile()),
                            tool.licencesDir(), area);
                    problems.forEach(p -> System.out.println("  - " + p));
                    System.out.println((problems.isEmpty() ? "RELEASE CHECK PASSED: " : "RELEASE CHECK FAILED: ")
                            + folder);
                    return problems.isEmpty() ? 0 : 1;
                }
                default -> {
                    System.err.println("unknown tool '" + tool.name() + "'; use match-sample, match-evaluate, release "
                            + "or release-check");
                    return 2;
                }
            }
            return 0;
        } catch (Exception e) {
            e.printStackTrace();
            return 1;
        }
    }
}
