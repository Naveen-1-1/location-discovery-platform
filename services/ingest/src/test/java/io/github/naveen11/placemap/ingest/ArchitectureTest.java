package io.github.naveen11.placemap.ingest;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Threads stay real, but Spring makes them: every thread comes from a named executor or scheduler bean that a
 * configuration class declares, so it is created, named and stopped in one place. Code elsewhere never starts its
 * own, and Spring's {@code @Async} and {@code @Scheduled} stay unused, since they hide which pool runs what.
 */
class ArchitectureTest {

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.naveen11.placemap.ingest");

    @Test
    void threadsComeOnlyFromExecutorBeans() {
        noClasses().that().areNotMetaAnnotatedWith(Configuration.class)
                .should().callConstructorWhere(target(owner(assignableTo(Thread.class))))
                .orShould().callMethodWhere(target(owner(type(Thread.class)))
                        .and(target(nameMatching("ofVirtual|ofPlatform|startVirtualThread"))))
                .orShould().callMethodWhere(target(owner(type(Executors.class))))
                .orShould().callConstructorWhere(target(owner(assignableTo(ExecutorService.class))))
                .orShould().callMethod(ForkJoinPool.class, "commonPool")
                .orShould().callMethod(CompletableFuture.class, "supplyAsync", Supplier.class)
                .orShould().callMethod(CompletableFuture.class, "runAsync", Runnable.class)
                .because("threads come from named executor and scheduler beans")
                .check(MAIN);
    }

    @Test
    void noSpringAsyncOrScheduledMethods() {
        noMethods().should().beAnnotatedWith(Async.class)
                .orShould().beAnnotatedWith(Scheduled.class)
                .because("threads come from named executor and scheduler beans")
                .check(MAIN);
    }

    @Test
    void noSpringAsyncOrSchedulingSwitchedOn() {
        noClasses().should().beAnnotatedWith(Async.class)
                .orShould().beAnnotatedWith(EnableAsync.class)
                .orShould().beAnnotatedWith(EnableScheduling.class)
                .because("threads come from named executor and scheduler beans")
                .check(MAIN);
    }
}
