package io.github.naveen11.placemap.ingest.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.ingest.OfflineHttp;
import io.github.naveen11.placemap.ingest.TestData;
import io.github.naveen11.placemap.ingest.TestDatabase;
import io.github.naveen11.placemap.ingest.run.RunCoordinator.Outcome;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import io.github.naveen11.placemap.ingest.source.BostonActiveFoodLicenses;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Whole runs against the real migrations on our PostGIS image. The "download" copies a local file,
 * so each test controls exactly what the publisher serves. Needs Docker; skipped (not failed) without it.
 */
@Testcontainers(disabledWithoutDocker = true)
class IngestRunContainerTest {

    private static final String SOURCE = BostonActiveFoodLicenses.ID;
    private static final int BATCH_SIZE = 7; // 40 fixture rows → 6 batches, so resume and parallel commits matter

    @Container
    static final PostgreSQLContainer POSTGIS = TestDatabase.postgis();

    private static DataSource dataSource;
    private static JdbcClient jdbc;

    @TempDir
    Path tmp;

    /** What the fake publisher serves on the next download. */
    private final AtomicReference<Path> published = new AtomicReference<>(TestData.BOSTON_LICENCES);

    @BeforeAll
    static void migrate() {
        dataSource = TestDatabase.migrated(POSTGIS);
        jdbc = JdbcClient.create(dataSource);
    }

    @BeforeEach
    void emptyTables() {
        jdbc.sql("""
                TRUNCATE ingest.reject, ingest.batch_done, place.source_record, ingest.run, batch.batch_job_instance
                RESTART IDENTITY CASCADE
                """).update();
    }

    @Test
    void firstRunStoresEveryRowAndCompletes() {
        assertThat(run()).isEqualTo(Outcome.COMPLETE);

        assertThat(count("SELECT count(*) FROM place.source_record WHERE source = ? AND retired_at IS NULL")).isEqualTo(40);
        assertThat(count("SELECT count(*) FROM place.source_record WHERE source = ? AND geom IS NULL")).isEqualTo(10);
        assertThat(count("SELECT count(*) FROM place.source_record WHERE source = ? AND category = 'food'")).isEqualTo(40);
        Map<String, Object> run = latestRun();
        assertThat(run).containsEntry("status", "COMPLETE")
                .containsEntry("total_rows", 40)
                .containsEntry("total_batches", 6)
                .containsEntry("rejected_rows", 0)
                .containsEntry("attempts", 1)
                .containsEntry("result_checksum", new RunStore(dataSource).checksum(SOURCE));
        assertThat(jdbc.sql("SELECT count(*) FROM ingest.batch_done WHERE keys IS NULL").query(Integer.class).single())
                .as("6 checkpoints, keys cleared at completion").isEqualTo(6);
        assertThat(jdbc.sql("SELECT count(*) FROM ingest.reject").query(Integer.class).single()).isZero();
    }

    @Test
    void anUnchangedDownloadIsSkipped() {
        run();

        assertThat(run()).isEqualTo(Outcome.UNCHANGED);
        assertThat(latestRun()).containsEntry("status", "UNCHANGED");
        assertThat(jdbc.sql("SELECT count(DISTINCT run_id) FROM ingest.batch_done").query(Integer.class).single()).isEqualTo(1);
    }

    /** The publisher's version marker (Boston's resource last-modified time) lets a run skip the download entirely. */
    @Test
    void anUnchangedPublisherVersionSkipsTheDownload() {
        var downloads = new AtomicInteger();
        var modified = new AtomicReference<>("2026-10-10T14:46:26.705686");
        var http = new HttpDownloader() {
            @Override
            public String getText(URI uri) {
                return "{\"result\": {\"last_modified\": \"" + modified.get() + "\"}}";
            }

            @Override
            public void download(URI uri, Path target) throws IOException {
                downloads.incrementAndGet();
                Files.copy(published.get(), target, StandardCopyOption.REPLACE_EXISTING);
            }
        };
        TestIngest.Importer coordinator = TestIngest.coordinator(dataSource, tmp.resolve("raw"), BATCH_SIZE, http,
                new BostonActiveFoodLicenses());

        assertThat(coordinator.run(SOURCE, false)).isEqualTo(Outcome.COMPLETE);
        assertThat(coordinator.run(SOURCE, false)).isEqualTo(Outcome.UNCHANGED);
        assertThat(downloads).as("second run read only the metadata").hasValue(1);
        assertThat((String) latestRun().get("source_version")).startsWith("ckan:2026-10-10T14:46:26.705686|");

        modified.set("2026-10-11T09:00:00"); // republished with the same content: downloaded, compared, unchanged
        assertThat(coordinator.run(SOURCE, false)).isEqualTo(Outcome.UNCHANGED);
        assertThat(downloads).hasValue(2);
    }

    /** Idempotency: running the same input again must give the same checksum and rewrite nothing. */
    @Test
    void aForcedRerunRewritesNothingAndGivesTheSameChecksum() {
        run();
        String first = checksum();

        assertThat(coordinator(dataSource).run(SOURCE, true)).isEqualTo(Outcome.COMPLETE);

        assertThat(checksum()).isEqualTo(first);
        assertThat(latestRun()).containsEntry("result_checksum", first);
        assertThat(count("SELECT count(*) FROM place.source_record WHERE source = ? AND changed_run <> 1")).isZero();
        assertThat(jdbc.sql("SELECT DISTINCT run_id FROM ingest.batch_done").query(Long.class).list())
                .as("the first run's checkpoints are pruned once the second completes").containsExactly(2L);
    }

    /**
     * A commit fails part-way (as if the process died there): the run stays open with some batches committed, and the
     * next attempt resumes it and ends at exactly the checksum of a clean run.
     */
    @Test
    void resumesAfterAFailedCommitAndEndsAtTheCleanChecksum() {
        run();
        String golden = checksum();
        emptyTables();

        Outcome failed = coordinator(CommitFaults.failingOnCommit(dataSource, 3)).run(SOURCE, false);

        assertThat(failed).isEqualTo(Outcome.ERROR);
        assertThat(latestRun()).containsEntry("status", "RUNNING").containsEntry("attempts", 1);
        int committed = jdbc.sql("SELECT count(*) FROM ingest.batch_done").query(Integer.class).single();
        assertThat(committed).isBetween(2, 5);

        assertThat(run()).isEqualTo(Outcome.COMPLETE);

        assertThat(latestRun()).containsEntry("status", "COMPLETE").containsEntry("attempts", 2);
        assertThat(checksum()).isEqualTo(golden);
        assertThat(count("SELECT count(*) FROM place.source_record WHERE source = ?")).isEqualTo(40);
    }

    /**
     * Crash test C2 in miniature: the third commit reaches the database but its acknowledgement is lost, as when the
     * connection drops right after COMMIT is sent. The writer retries the batch, finds it committed already, and the
     * run completes on its first attempt with the clean checksum and no duplicates.
     */
    @Test
    void aLostCommitAcknowledgementIsRetriedWithoutDuplicates() {
        run();
        String golden = checksum();
        emptyTables();

        Outcome outcome = coordinator(CommitFaults.losingCommitAcknowledgement(dataSource, 3)).run(SOURCE, false);

        assertThat(outcome).isEqualTo(Outcome.COMPLETE);
        assertThat(latestRun()).containsEntry("status", "COMPLETE").containsEntry("attempts", 1);
        assertThat(checksum()).isEqualTo(golden);
        assertThat(jdbc.sql("SELECT count(*) FROM ingest.batch_done").query(Integer.class).single()).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM place.source_record WHERE source = ?")).isEqualTo(40);
    }

    /**
     * Connections that keep dropping outlast the writer's retries, so the job fails; it is relaunched within the same
     * attempt (the restart redoes only the batches not committed) and completes with the clean checksum.
     */
    @Test
    void aJobFailedByDroppedConnectionsIsRelaunchedWithinTheAttempt() {
        run();
        String golden = checksum();
        emptyTables();

        Outcome outcome = coordinator(CommitFaults.droppingConnectionOnCommits(dataSource, 3, 20)).run(SOURCE, false);

        assertThat(outcome).isEqualTo(Outcome.COMPLETE);
        assertThat(latestRun()).containsEntry("status", "COMPLETE").containsEntry("attempts", 1);
        assertThat(jdbc.sql("SELECT count(*) FROM batch.batch_job_execution").query(Integer.class).single())
                .as("launches").isGreaterThan(1);
        assertThat(checksum()).isEqualTo(golden);
    }

    /**
     * An attempt can end with a step still marked running in an execution marked failed (its own last update was the
     * one a dropped connection lost), or with a status left UNKNOWN. The next attempt recovers them instead of being
     * refused a restart for ever.
     */
    @Test
    void stepsLeftRunningOrUnknownAreRecoveredAndTheRunResumes() {
        run();
        String golden = checksum();
        emptyTables();
        assertThat(coordinator(CommitFaults.failingOnCommit(dataSource, 3)).run(SOURCE, false)).isEqualTo(Outcome.ERROR);
        jdbc.sql("UPDATE batch.batch_step_execution SET status = 'STARTED' WHERE step_name LIKE 'batches:%'").update();
        jdbc.sql("UPDATE batch.batch_step_execution SET status = 'UNKNOWN' WHERE step_name = 'import'").update();

        assertThat(run()).isEqualTo(Outcome.COMPLETE);

        assertThat(latestRun()).containsEntry("status", "COMPLETE").containsEntry("attempts", 2);
        assertThat(checksum()).isEqualTo(golden);
    }

    @Test
    void retiresRecordsThatDisappearAndRevivesThemWhenTheyReturn() throws Exception {
        List<String> lines = Files.readAllLines(TestData.BOSTON_LICENCES);
        String removed = lines.get(6);
        String changedBefore = lines.get(8);
        var edited = new ArrayList<>(lines);
        edited.set(8, changedBefore.replace("Eating & Drinking,", "Eating & Drinking w/ Take Out,")); // still food
        edited.remove(6);

        run();
        publish(edited);
        assertThat(run()).isEqualTo(Outcome.COMPLETE);

        assertThat(count("SELECT count(*) FROM place.source_record WHERE source = ? AND retired_at IS NULL")).isEqualTo(39);
        assertThat(changedRuns()).containsEntry(key(removed), 1L).containsEntry(key(changedBefore), 2L);
        assertThat(count("SELECT count(*) FROM place.source_record WHERE source = ? AND changed_run = 1")).isEqualTo(39);

        publish(lines);
        assertThat(run()).isEqualTo(Outcome.COMPLETE);

        assertThat(count("SELECT count(*) FROM place.source_record WHERE source = ? AND retired_at IS NULL")).isEqualTo(40);
        assertThat(changedRuns()).containsEntry(key(removed), 3L).containsEntry(key(changedBefore), 3L);
    }

    @Test
    void tooManyRejectedRowsFailTheRunAndRetireNothing() throws Exception {
        run();
        List<String> lines = new ArrayList<>(Files.readAllLines(TestData.BOSTON_LICENCES));
        for (int i = 20; i < 23; i++) { // 3 of 40 rows = 7.5%, above the 2% limit
            lines.set(i, lines.get(i).replaceAll(",-?[0-9.]+,-?[0-9.]+$", ",0,0"));
        }
        publish(lines);

        assertThat(run()).isEqualTo(Outcome.FAILED);

        assertThat(latestRun()).containsEntry("status", "FAILED").containsEntry("rejected_rows", 3);
        assertThat((String) latestRun().get("error")).contains("3 of 40 rows rejected");
        assertThat(jdbc.sql("SELECT DISTINCT reason FROM ingest.reject").query(String.class).list())
                .containsExactly("zero coordinate");
        assertThat(count("SELECT count(*) FROM place.source_record WHERE source = ? AND retired_at IS NULL")).isEqualTo(40);
    }

    @Test
    void refusesToRetireMostOfTheSourceAtOnce() throws Exception {
        run();
        publish(Files.readAllLines(TestData.BOSTON_LICENCES).subList(0, 11)); // header + 10 rows: a truncated download

        assertThat(run()).isEqualTo(Outcome.FAILED);

        assertThat((String) latestRun().get("error")).contains("would retire 30 of 40");
        assertThat(count("SELECT count(*) FROM place.source_record WHERE source = ? AND retired_at IS NULL")).isEqualTo(40);
    }

    @Test
    void skipsWhileAnotherProcessHoldsTheSourceLock() throws Exception {
        try (Connection other = DriverManager.getConnection(TestDatabase.url(POSTGIS), POSTGIS.getUsername(),
                POSTGIS.getPassword());
                var lock = other.prepareStatement("SELECT pg_advisory_lock(hashtextextended(?, 0))")) {
            lock.setString(1, "placemap.ingest:" + SOURCE);
            lock.execute();

            assertThat(run()).isEqualTo(Outcome.LOCKED);
            assertThat(jdbc.sql("SELECT count(*) FROM ingest.run").query(Integer.class).single()).isZero();
        }
        // The other session ended, so Postgres released its lock.
        assertThat(run()).isEqualTo(Outcome.COMPLETE);
    }

    @Test
    void anEmptyDownloadFailsInsteadOfRetiringEverything() throws Exception {
        run();
        publish(Files.readAllLines(TestData.BOSTON_LICENCES).subList(0, 1));

        assertThat(run()).isEqualTo(Outcome.FAILED);
        assertThat((String) latestRun().get("error")).contains("no rows");
        assertThat(count("SELECT count(*) FROM place.source_record WHERE source = ? AND retired_at IS NULL")).isEqualTo(40);
    }

    @Test
    void aResumedRunWhoseSnapshotIsGoneFailsAndTheNextRunStartsFresh() throws Exception {
        coordinator(CommitFaults.failingOnCommit(dataSource, 2)).run(SOURCE, false);
        assertThat(latestRun()).containsEntry("status", "RUNNING");
        try (var files = Files.walk(tmp.resolve("raw"))) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                Files.delete(p);
            }
        }

        assertThat(run()).isEqualTo(Outcome.FAILED);
        assertThat((String) latestRun().get("error")).contains("missing or changed");

        assertThat(run()).isEqualTo(Outcome.COMPLETE);
        assertThat(latestRun()).containsEntry("run_id", 2L);
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private Outcome run() {
        return coordinator(dataSource).run(SOURCE, false);
    }

    private TestIngest.Importer coordinator(DataSource ds) {
        var http = new OfflineHttp() {
            @Override
            public void download(URI uri, Path target) throws IOException {
                Files.copy(published.get(), target, StandardCopyOption.REPLACE_EXISTING);
            }
        };
        return TestIngest.coordinator(ds, tmp.resolve("raw"), BATCH_SIZE, http, new BostonActiveFoodLicenses());
    }

    private void publish(List<String> lines) throws IOException {
        Path file = Files.createTempFile(tmp, "published", ".csv");
        Files.write(file, lines);
        published.set(file);
    }

    private int count(String sql) {
        return jdbc.sql(sql).param(SOURCE).query(Integer.class).single();
    }

    private String checksum() {
        return new RunStore(dataSource).checksum(SOURCE);
    }

    private Map<String, Object> latestRun() {
        return jdbc.sql("SELECT * FROM ingest.run ORDER BY run_id DESC LIMIT 1").query().singleRow();
    }

    private Map<String, Long> changedRuns() {
        return jdbc.sql("SELECT source_key, changed_run FROM place.source_record WHERE source = ?")
                .param(SOURCE)
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2)))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** The source key of a fixture line, computed the way the adapter does. */
    private static String key(String csvLine) throws IOException {
        String header = Files.readAllLines(TestData.BOSTON_LICENCES).getFirst();
        return new BostonActiveFoodLicenses().key(TestData.rows(header + "\n" + csvLine + "\n").getFirst());
    }
}
