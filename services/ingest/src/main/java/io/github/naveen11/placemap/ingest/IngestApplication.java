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
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The importer. No web server: it only talks to the sources and the database.
 *
 * <ul>
 *   <li>Service mode (default): the scheduler imports each source on its cadence and resumes unfinished runs.
 *   <li>Once mode ({@code --placemap.ingest.once=SOURCE}): run or resume one source, then exit with 0 if it completed
 *       or was unchanged, 1 otherwise. Used by tools/dev.sh ingest-once and the crash tests.
 *   <li>Tool mode ({@code --placemap.ingest.tool.name=match-sample|match-evaluate|release|release-check}): draw the
 *       matching evaluation's sample from the database, report precision and recall from its labels, build the
 *       ODbL data release, or check a built release, then exit.
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IngestApplication {

    private static final ZoneId BOSTON = ZoneId.of("America/New_York");

    public static void main(String[] args) {
        var context = SpringApplication.run(IngestApplication.class, args);
        var properties = context.getBean(IngestProperties.class);
        if (properties.toolMode()) {
            int code = runTool(properties, context.getBean(DataSource.class), context.getBean(SourcePriority.class),
                    context.getBean(CoverageArea.class));
            System.exit(SpringApplication.exit(context, () -> code));
        }
        if (properties.onceMode()) {
            Outcome outcome = context.getBean(IngestScheduler.class).runOnce(properties.once());
            int code = outcome == Outcome.COMPLETE || outcome == Outcome.UNCHANGED ? 0 : 1;
            // On SIGTERM the shutdown hook is already closing the context; exiting from here would block forever.
            if (context.isActive()) {
                System.exit(SpringApplication.exit(context, () -> code));
            }
        }
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
