package io.github.naveen11.placemap.livehub;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The showcase code does its own threading with java.util.concurrent, so Spring's {@code @Async} and
 * {@code @Scheduled} (which would hide it) are banned in live-hub and the concurrency library.
 */
class ArchitectureTest {

    private static final JavaClasses SHOWCASE = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.naveen11.placemap.livehub", "io.github.naveen11.placemap.concurrent");

    @Test
    void noSpringAsyncOrScheduledMethods() {
        noMethods().should().beAnnotatedWith(Async.class)
                .orShould().beAnnotatedWith(Scheduled.class)
                .because("threads in showcase code are explicit")
                .check(SHOWCASE);
    }

    @Test
    void noSpringAsyncOrSchedulingSwitchedOn() {
        noClasses().should().beAnnotatedWith(Async.class)
                .orShould().beAnnotatedWith(EnableAsync.class)
                .orShould().beAnnotatedWith(EnableScheduling.class)
                .because("threads in showcase code are explicit")
                .check(SHOWCASE);
    }
}
