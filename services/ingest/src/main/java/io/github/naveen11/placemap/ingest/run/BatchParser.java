package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.ingest.snapshot.RawBatch;
import io.github.naveen11.placemap.ingest.snapshot.RowRole;
import io.github.naveen11.placemap.ingest.source.CsvRow;
import io.github.naveen11.placemap.ingest.source.InspectionLine;
import io.github.naveen11.placemap.ingest.source.ParseContext;
import io.github.naveen11.placemap.ingest.source.RowResult;
import io.github.naveen11.placemap.ingest.source.SourceAdapter;
import io.github.naveen11.placemap.ingest.source.SourceRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;

/** Turns a batch's raw rows into records and rejections. Not thread-safe: one per partition (its parse context). */
final class BatchParser {

    private final SourceAdapter adapter;
    private final ParseContext context;

    BatchParser(SourceAdapter adapter, ParseContext context) {
        this.adapter = adapter;
        this.context = context;
    }

    ParsedBatch parse(RawBatch batch) {
        var records = new ArrayList<SourceRow>(batch.rows().size());
        var rejects = new ArrayList<ParsedBatch.Reject>();
        var inspections = new ArrayList<InspectionLine>();
        int skipped = 0;
        MessageDigest digest = DigestUtils.getSha256Digest();
        for (int i = 0; i < batch.rows().size(); i++) {
            CsvRow row = batch.rows().get(i);
            for (String value : row.values()) {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1f);
            }
            digest.update((byte) 0x1e);

            RowRole role = batch.roles()[i];
            if (role == RowRole.DUPLICATE) {
                rejects.add(new ParsedBatch.Reject(i, "repeated source key (the first row wins)", row.asMap()));
                continue;
            }
            // Each row yields at most one reject: a broken history line, or failing that a broken record. A row that
            // throws unexpectedly must not stop the run either; it is quarantined like any other bad row.
            RowResult result = null;
            try {
                adapter.inspection(row).ifPresent(inspections::add);
            } catch (IllegalArgumentException e) {
                result = new RowResult.Rejected(e.getMessage()); // the adapter's way of saying "unusable line"
            } catch (RuntimeException e) {
                result = new RowResult.Rejected("parse error: " + e);
            }
            if (result == null && role == RowRole.RECORD) {
                try {
                    result = adapter.parse(row, context);
                } catch (RuntimeException e) {
                    result = new RowResult.Rejected("parse error: " + e);
                }
            }
            switch (result) {
                case null -> {
                    // history only
                }
                case RowResult.Accepted accepted -> records.add(accepted.row());
                case RowResult.Rejected rejected -> rejects.add(new ParsedBatch.Reject(i, rejected.reason(), row.asMap()));
                case RowResult.Skipped ignored -> skipped++;
            }
        }
        return new ParsedBatch(batch.seq(), batch.rows().size(), records, rejects, skipped, inspections,
                Hex.encodeHexString(digest.digest()));
    }
}
