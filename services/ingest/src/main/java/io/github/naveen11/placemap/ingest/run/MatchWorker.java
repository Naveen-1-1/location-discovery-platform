package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.ingest.match.Matcher;
import io.github.naveen11.placemap.ingest.pipeline.Stage;

/** Match stage: decides each record's place against the run's frozen index. Runs on platform threads (H3 is native). */
final class MatchWorker implements Stage.Worker<ParsedBatch, MatchedBatch> {

    private final Matcher matcher;

    MatchWorker(Matcher matcher) {
        this.matcher = matcher;
    }

    @Override
    public MatchedBatch process(ParsedBatch batch) {
        return new MatchedBatch(batch, batch.records().stream().map(matcher::decide).toList());
    }
}
