package io.github.naveen11.placemap.ingest.snapshot;

import io.github.naveen11.placemap.ingest.source.CsvRow;
import java.util.List;

/**
 * Rows {@code firstRow .. firstRow + rows.size() - 1} of a snapshot. Batch {@code seq} always holds the same rows for
 * the same snapshot, so a batch redone after a crash writes exactly what the first attempt would have.
 *
 * @param duplicate per row: its key already appeared earlier in the snapshot
 */
public record RawBatch(int seq, int firstRow, List<CsvRow> rows, boolean[] duplicate) {
}
