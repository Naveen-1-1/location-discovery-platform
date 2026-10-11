package io.github.naveen11.placemap.ingest;

import io.github.naveen11.placemap.ingest.run.IngestScheduler;
import io.github.naveen11.placemap.ingest.run.RunCoordinator.Outcome;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The importer (ADR 0021). No web server: it only talks to the sources and the database.
 *
 * <ul>
 *   <li>Service mode (default): the scheduler imports each source on its cadence and resumes unfinished runs.
 *   <li>Once mode ({@code --placemap.ingest.once=SOURCE}): run or resume one source, then exit with 0 if it completed
 *       or was unchanged, 1 otherwise. Used by tools/dev.sh ingest-once and the crash tests.
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IngestApplication {

    public static void main(String[] args) {
        var context = SpringApplication.run(IngestApplication.class, args);
        var properties = context.getBean(IngestProperties.class);
        if (properties.onceMode()) {
            Outcome outcome = context.getBean(IngestScheduler.class).runOnce(properties.once());
            int code = outcome == Outcome.COMPLETE || outcome == Outcome.UNCHANGED ? 0 : 1;
            // On SIGTERM the shutdown hook is already closing the context; exiting from here would block forever.
            if (context.isActive()) {
                System.exit(SpringApplication.exit(context, () -> code));
            }
        }
    }
}
