package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.ingest.match.Matcher;
import io.github.naveen11.placemap.ingest.snapshot.RawBatch;
import org.springframework.batch.infrastructure.item.ItemProcessor;

/**
 * A partition's work on each batch between reading and writing: parse, place records that arrived without a location
 * (for sources that need it), and match every record against the run's frozen place index. One per partition: the
 * parser holds a parse context that is not thread-safe.
 */
class BatchProcessor implements ItemProcessor<RawBatch, MatchedBatch> {

    private final BatchParser parser;
    private final BatchGeocoder geocoder;
    private final Matcher matcher;

    /** @param geocoder null for sources whose records always carry a location */
    BatchProcessor(BatchParser parser, BatchGeocoder geocoder, Matcher matcher) {
        this.parser = parser;
        this.geocoder = geocoder;
        this.matcher = matcher;
    }

    @Override
    public MatchedBatch process(RawBatch raw) throws InterruptedException {
        ParsedBatch parsed = parser.parse(raw);
        if (geocoder != null) {
            parsed = geocoder.geocode(parsed);
        }
        return new MatchedBatch(parsed, parsed.records().stream().map(matcher::decide).toList());
    }
}
