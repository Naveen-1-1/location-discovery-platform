package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.ingest.pipeline.Stage;
import io.github.naveen11.placemap.ingest.snapshot.RawBatch;
import io.github.naveen11.placemap.ingest.source.CsvRow;
import io.github.naveen11.placemap.ingest.source.ParseContext;
import io.github.naveen11.placemap.ingest.source.RowResult;
import io.github.naveen11.placemap.ingest.source.SourceAdapter;
import io.github.naveen11.placemap.ingest.source.SourceRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;

/** Parse stage: turns raw rows into records and rejections. One instance per worker thread. */
final class ParseWorker implements Stage.Worker<RawBatch, ParsedBatch> {

    private final SourceAdapter adapter;
    private final ParseContext context;

    ParseWorker(SourceAdapter adapter, ParseContext context) {
        this.adapter = adapter;
        this.context = context;
    }

    @Override
    public ParsedBatch process(RawBatch batch) {
        var records = new ArrayList<SourceRow>(batch.rows().size());
        var rejects = new ArrayList<ParsedBatch.Reject>();
        int skipped = 0;
        MessageDigest digest = sha256();
        for (int i = 0; i < batch.rows().size(); i++) {
            CsvRow row = batch.rows().get(i);
            for (String value : row.values()) {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1f);
            }
            digest.update((byte) 0x1e);

            if (batch.duplicate()[i]) {
                rejects.add(new ParsedBatch.Reject(i, "repeated source key (the first row wins)", row.asMap()));
                continue;
            }
            RowResult result;
            try {
                result = adapter.parse(row, context);
            } catch (RuntimeException e) {
                // One odd row must not stop the run; it is quarantined like any other bad row.
                result = new RowResult.Rejected("parse error: " + e);
            }
            switch (result) {
                case RowResult.Accepted accepted -> records.add(accepted.row());
                case RowResult.Rejected rejected -> rejects.add(new ParsedBatch.Reject(i, rejected.reason(), row.asMap()));
                case RowResult.Skipped ignored -> skipped++;
            }
        }
        return new ParsedBatch(batch.seq(), batch.rows().size(), records, rejects, skipped,
                HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK ships SHA-256", e);
        }
    }
}
