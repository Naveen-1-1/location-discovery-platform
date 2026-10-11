package io.github.naveen11.placemap.ingest.snapshot;

import io.github.naveen11.placemap.ingest.source.CsvRow;
import io.github.naveen11.placemap.ingest.source.SourceAdapter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.apache.commons.csv.CSVRecord;

/**
 * One pass over a snapshot before any batch is cut: counts rows and decides each row's {@link RowRole}.
 *
 * <ul>
 *   <li>Keys must be unique: the first row with a key wins and later ones are duplicates.
 *   <li>{@link SourceAdapter#linesPerRecord() Lines per record}: the line with the greatest version describes the
 *       record (first one on a tie); the others are history only.
 * </ul>
 *
 * Deciding this up front keeps the result independent of the order in which batches commit: if two batches each
 * described the same record, whichever committed last would win, and two runs over the same input could differ.
 *
 * @param duplicates row numbers (0-based, in file order) rejected as repeated keys
 * @param historyOnly row numbers that only add history to a record described by another row
 */
public record SnapshotScan(int rows, BitSet duplicates, BitSet historyOnly) {

    public static SnapshotScan of(Path file, SourceAdapter adapter) throws IOException {
        List<String> columns = adapter.columns();
        boolean lines = adapter.linesPerRecord();
        var seen = new HashSet<String>();
        var newest = new HashMap<String, Integer>();       // lines per record: key → row describing it
        var newestVersion = new HashMap<String, String>();
        var duplicates = new BitSet();
        var historyOnly = new BitSet();
        int rows = 0;
        try (var parser = SnapshotCsv.open(file)) {
            SnapshotCsv.checkHeader(parser, columns, file);
            for (CSVRecord record : parser) {
                var row = new CsvRow(columns, record);
                String key = adapter.key(row);
                if (key != null && lines) {
                    String version = adapter.recordVersion(row);
                    Integer previous = newest.get(key);
                    if (previous == null || version.compareTo(newestVersion.get(key)) > 0) {
                        if (previous != null) {
                            historyOnly.set(previous);
                        }
                        newest.put(key, rows);
                        newestVersion.put(key, version);
                    } else {
                        historyOnly.set(rows);
                    }
                } else if (key != null && !seen.add(key)) {
                    duplicates.set(rows);
                }
                rows++;
            }
        }
        return new SnapshotScan(rows, duplicates, historyOnly);
    }

    public RowRole role(int row) {
        if (duplicates.get(row)) {
            return RowRole.DUPLICATE;
        }
        return historyOnly.get(row) ? RowRole.HISTORY_ONLY : RowRole.RECORD;
    }

    public int batches(int batchSize) {
        return (rows + batchSize - 1) / batchSize;
    }
}
