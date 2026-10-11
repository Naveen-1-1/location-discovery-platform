package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.ingest.match.MatchDecision;
import java.util.List;

/** A parsed batch plus one matching decision per record, in the same order as {@code batch.records()}. */
public record MatchedBatch(ParsedBatch batch, List<MatchDecision> decisions) {

    public MatchedBatch {
        if (decisions.size() != batch.records().size()) {
            throw new IllegalArgumentException("one decision per record");
        }
    }
}
