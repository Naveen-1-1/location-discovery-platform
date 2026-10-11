package io.github.naveen11.placemap.ingest.snapshot;

import io.github.naveen11.placemap.ingest.source.CsvRow;
import io.github.naveen11.placemap.ingest.source.SourceAdapter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import org.apache.commons.csv.CSVRecord;

/**
 * One pass over a snapshot before any batch is cut: counts rows and marks repeated source keys. The first row with a
 * key wins; later ones are rejected. Deciding this up front keeps the result independent of the order in which
 * batches commit: if two batches both carried the key, whichever committed last would win, and two runs over the
 * same input could end with different data.
 *
 * @param duplicates row numbers (0-based, in file order) whose key appeared on an earlier row
 */
public record SnapshotScan(int rows, BitSet duplicates) {

    public static SnapshotScan of(Path file, SourceAdapter adapter) throws IOException {
        List<String> columns = adapter.columns();
        var seen = new HashSet<String>();
        var duplicates = new BitSet();
        int rows = 0;
        try (var parser = SnapshotCsv.open(file)) {
            SnapshotCsv.checkHeader(parser, columns, file);
            for (CSVRecord record : parser) {
                String key = adapter.key(new CsvRow(columns, record));
                if (key != null && !seen.add(key)) {
                    duplicates.set(rows);
                }
                rows++;
            }
        }
        return new SnapshotScan(rows, duplicates);
    }

    public int batches(int batchSize) {
        return (rows + batchSize - 1) / batchSize;
    }
}
