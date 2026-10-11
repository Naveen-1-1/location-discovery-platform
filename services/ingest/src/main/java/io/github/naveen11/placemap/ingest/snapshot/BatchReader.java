package io.github.naveen11.placemap.ingest.snapshot;

import io.github.naveen11.placemap.concurrent.BoundedChannel;
import io.github.naveen11.placemap.ingest.pipeline.StageListener;
import io.github.naveen11.placemap.ingest.source.CsvRow;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.apache.commons.csv.CSVRecord;

/**
 * Cuts a snapshot into consecutive batches of {@code batchSize} rows and hands them to the parse stage. Batches that
 * are already committed are read past but not sent. When the channel is full, {@code put} blocks and reading pauses:
 * that is the backpressure that keeps memory flat when the database is slow. The listener sees both: the time spent
 * reading each batch, and the time spent waiting for room.
 */
public final class BatchReader {

    private final Path file;
    private final List<String> columns;
    private final int batchSize;
    private final SnapshotScan scan;
    private final Set<Integer> alreadyDone;

    public BatchReader(Path file, List<String> columns, int batchSize, SnapshotScan scan, Set<Integer> alreadyDone) {
        this.file = file;
        this.columns = columns;
        this.batchSize = batchSize;
        this.scan = scan;
        this.alreadyDone = alreadyDone;
    }

    /** Sends every pending batch, stopping early (between batches) if asked to. Does not close the channel. */
    public void readInto(BoundedChannel<RawBatch> out, BooleanSupplier stopRequested, StageListener listener)
            throws IOException, InterruptedException {
        try (var parser = SnapshotCsv.open(file)) {
            SnapshotCsv.checkHeader(parser, columns, file);
            Iterator<CSVRecord> records = parser.iterator();
            int row = 0;
            for (int seq = 0; records.hasNext(); seq++) {
                if (stopRequested.getAsBoolean()) {
                    return;
                }
                long start = System.nanoTime();
                int firstRow = row;
                var rows = new ArrayList<CsvRow>(batchSize);
                while (rows.size() < batchSize && records.hasNext()) {
                    rows.add(new CsvRow(columns, records.next()));
                    row++;
                }
                if (alreadyDone.contains(seq)) {
                    continue;
                }
                var roles = new RowRole[rows.size()];
                for (int i = 0; i < roles.length; i++) {
                    roles[i] = scan.role(firstRow + i);
                }
                var batch = new RawBatch(seq, firstRow, List.copyOf(rows), roles);
                long putStart = System.nanoTime();
                listener.processed(putStart - start);
                out.put(batch);
                listener.blocked(System.nanoTime() - putStart);
            }
            if (row != scan.rows()) {
                throw new SnapshotException("snapshot " + file + " changed while being read: " + row + " rows, scan saw "
                        + scan.rows());
            }
        }
    }
}
