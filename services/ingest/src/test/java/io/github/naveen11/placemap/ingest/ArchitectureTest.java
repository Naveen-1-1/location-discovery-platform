package io.github.naveen11.placemap.ingest;

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
 * Every thread in the importer comes from a named Spring executor bean, so Spring's {@code @Async} and
 * {@code @Scheduled} (which would hide them behind annotations) stay banned.
 */
class ArchitectureTest {

    private static final JavaClasses SHOWCASE = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.naveen11.placemap.ingest");

    @Test
    void noSpringAsyncOrScheduledMethods() {
        noMethods().should().beAnnotatedWith(Async.class)
                .orShould().beAnnotatedWith(Scheduled.class)
                .because("threads come from named executor beans")
                .check(SHOWCASE);
    }

    @Test
    void noSpringAsyncOrSchedulingSwitchedOn() {
        noClasses().should().beAnnotatedWith(Async.class)
                .orShould().beAnnotatedWith(EnableAsync.class)
                .orShould().beAnnotatedWith(EnableScheduling.class)
                .because("threads come from named executor beans")
                .check(SHOWCASE);
    }
}
