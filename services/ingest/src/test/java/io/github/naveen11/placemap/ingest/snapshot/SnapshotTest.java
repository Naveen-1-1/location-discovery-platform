package io.github.naveen11.placemap.ingest.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naveen11.placemap.ingest.OfflineHttp;
import io.github.naveen11.placemap.ingest.TestData;
import io.github.naveen11.placemap.ingest.source.BostonActiveFoodLicenses;
import io.github.naveen11.placemap.ingest.source.CsvRow;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/** Scanning and batch cutting: the same snapshot must always give the same batches. */
class SnapshotTest {

    private final BostonActiveFoodLicenses adapter = new BostonActiveFoodLicenses();

    @TempDir
    Path dir;

    @Test
    void scanCountsRowsAndMarksLaterRepeatsOfAKey() throws Exception {
        // Repeat fixture row 3 at the end: the original stays, the copy is the duplicate.
        List<String> lines = Files.readAllLines(TestData.BOSTON_LICENCES);
        lines.add(lines.get(3));
        Path file = Files.write(dir.resolve("snapshot.csv"), lines);

        SnapshotScan scan = SnapshotScan.of(file, adapter);

        assertThat(scan.rows()).isEqualTo(41);
        assertThat(scan.duplicates().stream().boxed().toList()).containsExactly(40);
        assertThat(scan.batches(10)).isEqualTo(5);
        assertThat(scan.batches(41)).isEqualTo(1);
    }

    @Test
    void refusesASnapshotWithDifferentColumns() throws Exception {
        List<String> lines = new ArrayList<>(Files.readAllLines(TestData.BOSTON_LICENCES));
        lines.set(0, lines.getFirst() + ",dayphn_cleaned");
        Path file = Files.write(dir.resolve("snapshot.csv"), lines);

        assertThatThrownBy(() -> SnapshotScan.of(file, adapter))
                .isInstanceOf(SnapshotException.class)
                .hasMessageContaining("dayphn_cleaned");
    }

    @Test
    void aPinnedSnapshotIsCopiedInsteadOfDownloaded() throws Exception {
        var store = new SnapshotStore(dir.resolve("raw"), new OfflineHttp() {
            @Override
            public void download(URI uri, Path target) throws IOException {
                throw new IOException("a pinned run must not download " + uri);
            }
        }, TestData.BOSTON_LICENCES);

        Snapshot snapshot = store.fetch(adapter, TestData.catalog().get(adapter.sourceId()), 7);

        assertThat(store.pinned()).isTrue();
        assertThat(Files.mismatch(snapshot.file(), TestData.BOSTON_LICENCES)).isEqualTo(-1);
        assertThat(snapshot.sha256()).isEqualTo(SnapshotStore.sha256(TestData.BOSTON_LICENCES));
        assertThat(Files.readString(snapshot.file().resolveSibling("manifest.json"))).contains("file:");
    }

    @Test
    void cutsTheSameBatchesEveryTimeAndSkipsCommittedOnes() throws Exception {
        SnapshotScan scan = SnapshotScan.of(TestData.BOSTON_LICENCES, adapter);

        List<RawBatch> first = readAll(scan, Set.of(), 0, 7);
        List<RawBatch> second = readAll(scan, Set.of(), 0, 7);
        List<RawBatch> resumed = readAll(scan, Set.of(0, 2), 0, 7);

        assertThat(first).extracting(RawBatch::seq).containsExactly(0, 1, 2, 3, 4, 5, 6);
        assertThat(first).extracting(RawBatch::firstRow).containsExactly(0, 6, 12, 18, 24, 30, 36);
        assertThat(first.getLast().rows()).hasSize(4); // 40 rows in batches of 6
        assertThat(values(first)).isEqualTo(values(second));
        assertThat(resumed).extracting(RawBatch::seq).containsExactly(1, 3, 4, 5, 6);
        assertThat(values(resumed.getFirst())).isEqualTo(values(first.get(1)));
    }

    @Test
    void aPartitionReadsOnlyItsRangeOfBatches() throws Exception {
        SnapshotScan scan = SnapshotScan.of(TestData.BOSTON_LICENCES, adapter);

        List<RawBatch> all = readAll(scan, Set.of(), 0, 7);
        List<RawBatch> middle = readAll(scan, Set.of(3), 2, 5);

        assertThat(middle).extracting(RawBatch::seq).containsExactly(2, 4);
        assertThat(values(middle.getFirst())).isEqualTo(values(all.get(2)));
        assertThat(middle.getLast().firstRow()).isEqualTo(24);
    }

    @Test
    void theLastPartitionNoticesAFileThatChangedSinceTheScan() throws Exception {
        SnapshotScan scan = SnapshotScan.of(TestData.BOSTON_LICENCES, adapter);
        var grown = new SnapshotScan(scan.rows() - 1, scan.duplicates(), scan.historyOnly());

        assertThatThrownBy(() -> readAll(grown, Set.of(), 4, 7))
                .isInstanceOf(SnapshotException.class).hasMessageContaining("changed while being read");
    }

    private List<RawBatch> readAll(SnapshotScan scan, Set<Integer> done, int first, int end) {
        var reader = new BatchReader(TestData.BOSTON_LICENCES, adapter.columns(), 6, scan, done, first, end);
        reader.open(new ExecutionContext());
        try {
            var batches = new ArrayList<RawBatch>();
            for (RawBatch b; (b = reader.read()) != null; ) {
                batches.add(b);
            }
            return batches;
        } finally {
            reader.close();
        }
    }

    private static List<List<String>> values(List<RawBatch> batches) {
        return batches.stream().flatMap(b -> b.rows().stream()).map(CsvRow::values).toList();
    }

    private static List<List<String>> values(RawBatch batch) {
        return batch.rows().stream().map(CsvRow::values).toList();
    }
}
