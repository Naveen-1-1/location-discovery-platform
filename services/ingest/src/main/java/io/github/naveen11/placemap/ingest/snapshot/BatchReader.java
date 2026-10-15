package io.github.naveen11.placemap.ingest.snapshot;

import io.github.naveen11.placemap.ingest.source.CsvRow;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamReader;

/**
 * Reads one partition's range of a snapshot's batches, {@code first} up to (not including) {@code end}, each of
 * {@code batchSize} consecutive rows. Batch {@code seq} always holds the same rows for the same snapshot.
 *
 * <p>Batches already committed are read past but not returned. Which ones those are comes from the database
 * ({@code ingest.batch_done}, written in each batch's own transaction), never from a saved reader position, so a
 * resumed run redoes exactly the batches that did not commit, however the previous attempt ended.
 */
public class BatchReader implements ItemStreamReader<RawBatch> {

    private final Path file;
    private final List<String> columns;
    private final int batchSize;
    private final SnapshotScan scan;
    private final Set<Integer> alreadyDone;
    private final int first;
    private final int end;

    private CSVParser parser;
    private Iterator<CSVRecord> records;
    private int seq;
    private int row;

    public BatchReader(Path file, List<String> columns, int batchSize, SnapshotScan scan, Set<Integer> alreadyDone,
            int first, int end) {
        this.file = file;
        this.columns = columns;
        this.batchSize = batchSize;
        this.scan = scan;
        this.alreadyDone = alreadyDone;
        this.first = first;
        this.end = end;
    }

    @Override
    public void open(ExecutionContext executionContext) {
        try {
            parser = SnapshotCsv.open(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        SnapshotCsv.checkHeader(parser, columns, file);
        records = parser.iterator();
        for (seq = 0, row = 0; seq < first && records.hasNext(); seq++) {
            skipBatch();
        }
    }

    @Override
    public RawBatch read() {
        while (seq < end && records.hasNext()) {
            int batch = seq++;
            if (alreadyDone.contains(batch)) {
                skipBatch();
                continue;
            }
            int firstRow = row;
            var rows = new ArrayList<CsvRow>(batchSize);
            while (rows.size() < batchSize && records.hasNext()) {
                rows.add(new CsvRow(columns, records.next()));
                row++;
            }
            var roles = new RowRole[rows.size()];
            for (int i = 0; i < roles.length; i++) {
                roles[i] = scan.role(firstRow + i);
            }
            return new RawBatch(batch, firstRow, List.copyOf(rows), roles);
        }
        // The partition that reads to the end checks the file still has the rows the scan counted.
        if (seq < end || (end == scan.batches(batchSize) && (records.hasNext() || row != scan.rows()))) {
            throw new SnapshotException("snapshot " + file + " changed while being read: batch " + seq + " of "
                    + scan.batches(batchSize) + ", row " + row + ", scan saw " + scan.rows() + " rows");
        }
        return null;
    }

    private void skipBatch() {
        for (int i = 0; i < batchSize && records.hasNext(); i++) {
            records.next();
            row++;
        }
    }

    @Override
    public void close() {
        if (parser != null) {
            try {
                parser.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            parser = null;
        }
    }
}
