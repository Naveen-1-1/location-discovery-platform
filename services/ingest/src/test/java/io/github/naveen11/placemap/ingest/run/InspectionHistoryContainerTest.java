package io.github.naveen11.placemap.ingest.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.ingest.OfflineHttp;
import io.github.naveen11.placemap.ingest.run.RunCoordinator.Outcome;
import io.github.naveen11.placemap.ingest.source.BostonFoodInspections;
import io.github.naveen11.placemap.ingest.source.CambridgeSanitaryInspections;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Inspection history through the real pipeline and migrations (ADR 0034): 36 real Boston lines (3 licences, 20
 * inspections, 23 violation lines) and 26 real Cambridge lines (4 establishments, 9 inspections, 24 violations). Batches of 5 rows, so one licence's lines fall into
 * batches that commit in parallel. Needs Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class InspectionHistoryContainerTest {

    private static final Path POSTGIS_IMAGE_DIR = Path.of("../../infra/docker/postgis").toAbsolutePath().normalize();
    private static final Path BOSTON = Path.of("src/test/resources/fixtures/boston_food_inspections.csv").toAbsolutePath();
    private static final Path CAMBRIDGE =
            Path.of("src/test/resources/fixtures/cambridge_sanitary_inspections.csv").toAbsolutePath();

    @Container
    static final GenericContainer<?> POSTGIS = new GenericContainer<>(
                    new ImageFromDockerfile("placemap/postgis-test", false).withFileFromPath(".", POSTGIS_IMAGE_DIR))
            .withEnv("POSTGRES_DB", "placemap")
            .withEnv("POSTGRES_PASSWORD", "test")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));

    private static DataSource dataSource;
    private static JdbcClient jdbc;

    @TempDir
    Path tmp;

    @BeforeAll
    static void migrate() {
        String url = "jdbc:postgresql://%s:%d/placemap?reWriteBatchedInserts=true".formatted(POSTGIS.getHost(),
                POSTGIS.getMappedPort(5432));
        dataSource = new DriverManagerDataSource(url, "postgres", "test");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
    }

    @BeforeEach
    void emptyTables() {
        jdbc.sql("""
                TRUNCATE place.inspection_violation, place.inspection, place.violation_code, place.inspection_summary,
                         ingest.reject, ingest.batch_done, place.match_review, place.place_source_link,
                         place.source_record, place.place, ingest.run RESTART IDENTITY CASCADE
                """).update();
    }

    @Test
    void storesTheFullHistoryOnceAndSummarisesIt() {
        assertThat(run(BostonFoodInspections.ID, dataSource, false)).isEqualTo(Outcome.COMPLETE);

        assertThat(counts(BostonFoodInspections.ID)).isEqualTo(Map.of(
                "records", 3, "inspections", 20, "violations", 23, "codes", 12, "summaries", 3));
        assertThat(jdbc.sql("SELECT source_key, latest_result FROM place.inspection_summary ORDER BY source_key")
                .query((rs, i) -> rs.getString(1) + "=" + rs.getString(2)).list())
                .containsExactly("384048=HE_Pass", "526046=HE_Filed", "545262=HE_Pass");
        assertThat(jdbc.sql("SELECT sum(inspections) FROM place.inspection_summary").query(Integer.class).single())
                .isEqualTo(20);
        assertThat(jdbc.sql("SELECT category FROM place.source_record WHERE source_key = '384048'")
                .query(String.class).single()).isEqualTo("grocery");
    }

    @Test
    void reImportingTheSameHistoryAddsNothing() {
        run(BostonFoodInspections.ID, dataSource, false);

        assertThat(run(BostonFoodInspections.ID, dataSource, true)).isEqualTo(Outcome.COMPLETE);

        assertThat(counts(BostonFoodInspections.ID)).containsEntry("inspections", 20).containsEntry("violations", 23);
        assertThat(jdbc.sql("SELECT count(*) FROM place.inspection WHERE created_run <> 1").query(Integer.class).single())
                .as("no inspection rewritten by the second run").isZero();
    }

    @Test
    void aCrashMidHistoryResumesToTheSameHistory() {
        run(BostonFoodInspections.ID, dataSource, false);
        Map<String, Integer> clean = counts(BostonFoodInspections.ID);
        List<String> cleanSummary = summary();
        emptyTables();

        assertThat(run(BostonFoodInspections.ID, failingOnCommit(3), false)).isEqualTo(Outcome.ERROR);
        assertThat(run(BostonFoodInspections.ID, dataSource, false)).isEqualTo(Outcome.COMPLETE);

        assertThat(counts(BostonFoodInspections.ID)).isEqualTo(clean);
        assertThat(summary()).isEqualTo(cleanSummary);
    }

    @Test
    void cambridgeCasesBecomeInspectionsWithFailedChecks() {
        assertThat(run(CambridgeSanitaryInspections.ID, dataSource, false)).isEqualTo(Outcome.COMPLETE);

        assertThat(counts(CambridgeSanitaryInspections.ID)).containsEntry("records", 4)
                .containsEntry("inspections", 9).containsEntry("violations", 24).containsEntry("summaries", 4);
        assertThat(jdbc.sql("SELECT count(*) FROM place.inspection_violation WHERE status <> 'Fail'")
                .query(Integer.class).single()).isZero();
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private Outcome run(String source, DataSource ds, boolean force) {
        var http = new OfflineHttp() {
            @Override
            public void download(URI uri, Path target) throws IOException {
                Files.copy("data.boston.gov".equals(uri.getHost()) ? BOSTON : CAMBRIDGE, target,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        };
        RunCoordinator coordinator = TestIngest.coordinator(ds, TestIngest.properties(tmp.resolve("raw"), 5, 0.2), http,
                new BostonFoodInspections(), new CambridgeSanitaryInspections());
        return coordinator.run(source, new RunControl(), force);
    }

    private Map<String, Integer> counts(String source) {
        return Map.of(
                "records", count("SELECT count(*) FROM place.source_record WHERE source = ?", source),
                "inspections", count("SELECT count(*) FROM place.inspection WHERE source = ?", source),
                "violations", count("""
                        SELECT count(*) FROM place.inspection_violation v
                        JOIN place.inspection i USING (inspection_id) WHERE i.source = ?
                        """, source),
                "codes", count("SELECT count(*) FROM place.violation_code WHERE source = ?", source),
                "summaries", count("SELECT count(*) FROM place.inspection_summary WHERE source = ?", source));
    }

    private int count(String sql, String source) {
        return jdbc.sql(sql).param(source).query(Integer.class).single();
    }

    private List<String> summary() {
        return jdbc.sql("""
                        SELECT source_key || ' ' || latest_result || ' ' || latest_at || ' ' || inspections || ' '
                               || failed_checks_12m
                        FROM place.inspection_summary ORDER BY source_key
                        """)
                .query(String.class).list();
    }

    /** A data source whose n-th commit (counted across all connections) rolls back and throws instead. */
    private static DataSource failingOnCommit(int n) {
        var commits = new AtomicInteger();
        return new DelegatingDataSource(dataSource) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection real = super.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                            if (method.getName().equals("commit") && commits.incrementAndGet() == n) {
                                real.rollback();
                                throw new SQLException("injected commit failure", "XX000");
                            }
                            try {
                                return method.invoke(real, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
            }
        };
    }
}
