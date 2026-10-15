package io.github.naveen11.placemap.ingest;

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
 *
 * Once and tool mode run in {@link IngestRunner} as the application starts; their exit code reaches the JVM through
 * Spring's exit code generators.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IngestApplication {

    public static void main(String[] args) {
        var context = SpringApplication.run(IngestApplication.class, args);
        var properties = context.getBean(IngestProperties.class);
        // On SIGTERM the shutdown hook is already closing the context; exiting from here would block forever.
        if ((properties.onceMode() || properties.toolMode()) && context.isActive()) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
