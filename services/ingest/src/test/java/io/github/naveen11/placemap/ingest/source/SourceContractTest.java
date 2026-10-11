package io.github.naveen11.placemap.ingest.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.naveen11.placemap.ingest.LiveHttp;
import io.github.naveen11.placemap.ingest.TestData;
import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Schema contract with each publisher: fetches today's real data through the real adapter and checks that the
 * snapshot has exactly the columns the importer reads, that rows still parse, and that rejections stay under the
 * 2% limit a run enforces. A publisher that renames or drops a field fails here before it fails an import.
 *
 * <p>Talks to the publishers, so it runs only on request ({@code -Dplacemap.contract=true}), and nightly in CI.
 * Sources needing a key are skipped while the key is missing.
 */
@EnabledIfSystemProperty(named = "placemap.contract", matches = "true")
class SourceContractTest {

    private static final double MAX_REJECT_SHARE = 0.02;
    private static final HttpDownloader HTTP = LiveHttp.create();

    static Stream<SourceAdapter> adapters() {
        return Stream.of(
                new MassgisHospitals(TestData.area()),
                new FdicBankLocations(),
                new AfdcFuelStations(name -> Optional.ofNullable(System.getenv(name)).orElse("")),
                new BostonOpenSpace(),
                new BostonPublicLibraries(),
                new BostonBcyfCenters());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    void thePublisherStillSendsWhatTheImporterReads(SourceAdapter adapter, @TempDir Path dir) throws Exception {
        SourceDefinition source = TestData.catalog().get(adapter.sourceId());
        Optional<String> notReady = adapter.notReady(source);
        assumeTrue(notReady.isEmpty(), () -> notReady.orElse(""));

        Path snapshot = dir.resolve("snapshot.csv");
        adapter.fetch(source, snapshot, HTTP);
        String version = adapter.remoteVersion(source, HTTP);

        try (BufferedReader reader = Files.newBufferedReader(snapshot, StandardCharsets.UTF_8);
                CSVParser parser = CSVParser.parse(reader,
                        CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get())) {
            assertThat(parser.getHeaderNames()).as("snapshot columns").isEqualTo(adapter.columns());
        }
        List<CsvRow> rows = TestData.rows(snapshot);
        ParseContext context = TestData.parseContext();
        Map<String, Integer> outcomes = new TreeMap<>();
        Map<String, Integer> reasons = new TreeMap<>();
        for (CsvRow row : rows) {
            RowResult result = adapter.parse(row, context);
            outcomes.merge(result.getClass().getSimpleName(), 1, Integer::sum);
            switch (result) {
                case RowResult.Rejected r -> reasons.merge("rejected: " + r.reason(), 1, Integer::sum);
                case RowResult.Skipped s -> reasons.merge("skipped: " + s.reason().replaceFirst(":.*", ""), 1, Integer::sum);
                case RowResult.Accepted a -> reasons.merge("category " + a.row().category(), 1, Integer::sum);
            }
        }
        System.out.printf("%s: %d rows %s %s version=%s%n", adapter.sourceId(), rows.size(), outcomes, reasons, version);

        int rejected = outcomes.getOrDefault("Rejected", 0);
        assertThat(outcomes.getOrDefault("Accepted", 0)).as("accepted rows").isPositive();
        assertThat((double) rejected / rows.size()).as("reject share").isLessThanOrEqualTo(MAX_REJECT_SHARE);
    }
}
