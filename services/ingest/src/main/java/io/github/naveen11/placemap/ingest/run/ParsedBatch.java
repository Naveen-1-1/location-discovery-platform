package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.ingest.source.InspectionLine;
import io.github.naveen11.placemap.ingest.source.SourceRow;
import java.util.List;
import java.util.Map;

/**
 * A parsed batch, ready to commit in one transaction.
 *
 * @param rows rows in the raw batch (accepted + rejected + skipped)
 * @param skipped valid rows that are out of scope (see {@code RowResult.Skipped})
 * @param inspections inspection history lines, for sources that carry them (ADR 0034)
 * @param checksum sha-256 of the raw rows, stored with the checkpoint
 */
public record ParsedBatch(int seq, int rows, List<SourceRow> records, List<Reject> rejects, int skipped,
        List<InspectionLine> inspections, String checksum) {

    /** @param rowNo position within the batch */
    public record Reject(int rowNo, String reason, Map<String, String> record) {
    }
}
