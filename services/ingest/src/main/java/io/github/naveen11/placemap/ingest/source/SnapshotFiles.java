package io.github.naveen11.placemap.ingest.source;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

/**
 * Writes the snapshot of a source whose publisher serves JSON or a zip rather than the CSV we store. Rows are sorted
 * by their first column (the source's key), so an unchanged dataset gives a byte-identical snapshot and the run is
 * skipped, whatever order the publisher used. Written to {@code <file>.part} and renamed, like a download.
 */
final class SnapshotFiles {

    private SnapshotFiles() {
    }

    static void writeSorted(Path target, List<String> columns, List<List<String>> rows) throws IOException {
        var sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(List::getFirst));
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try (Writer out = Files.newBufferedWriter(part, StandardCharsets.UTF_8);
                var printer = new CSVPrinter(out, CSVFormat.RFC4180.builder().setHeader(columns.toArray(String[]::new)).get())) {
            for (List<String> row : sorted) {
                if (row.size() != columns.size()) {
                    throw new IllegalArgumentException("row has " + row.size() + " values for " + columns.size() + " columns");
                }
                printer.printRecord(row);
            }
        }
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
