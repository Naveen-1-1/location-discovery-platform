package io.github.naveen11.placemap.ingest.run;

import io.github.naveen11.placemap.ingest.catalog.CoverageArea;
import io.github.naveen11.placemap.ingest.geocode.AddressRule;
import io.github.naveen11.placemap.ingest.geocode.Candidate;
import io.github.naveen11.placemap.ingest.geocode.Geocode;
import io.github.naveen11.placemap.ingest.geocode.Geocoding;
import io.github.naveen11.placemap.ingest.pipeline.Stage;
import io.github.naveen11.placemap.ingest.source.SourceRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Geocode stage (plan: "reproject / geocode"), between parse and match, for sources whose records can arrive without
 * a location. Runs on platform threads like its neighbours.
 *
 * <ol>
 *   <li>Turn each unplaced record's address into a query; one cache lookup answers the batch's known ones.
 *   <li>Send the rest to the geocoder, one virtual thread per query. {@link Geocoding} caps the calls in flight at four
 *       and their rate, across all workers and runs, so the worker count never changes the load on the service.
 *   <li>Store the decisions, then read them back: if two batches asked for the same address at once, both use the
 *       one stored first, so the answer never depends on which batch won.
 *   <li>A call that fails (timeout, HTTP error) leaves its records unplaced and is not cached; the next run asks again.
 * </ol>
 *
 * Decisions survive a crash in the cache, so a resumed run places records exactly as the first attempt did.
 */
final class GeocodeWorker implements Stage.Worker<ParsedBatch, ParsedBatch> {

    private static final Logger log = LoggerFactory.getLogger(GeocodeWorker.class);

    private final String source;
    private final Geocoding geocoding;
    private final CoverageArea.Checker area;
    private final IngestMetrics.RunMetrics metrics;

    GeocodeWorker(String source, Geocoding geocoding, CoverageArea.Checker area, IngestMetrics.RunMetrics metrics) {
        this.source = source;
        this.geocoding = geocoding;
        this.area = area;
        this.metrics = metrics;
    }

    @Override
    public ParsedBatch process(ParsedBatch batch) throws InterruptedException {
        List<SourceRow> records = batch.records();
        String[] queryOf = new String[records.size()];
        var example = new TreeMap<String, SourceRow>(); // query → the first record asking it; sorted, so calls go in order
        for (int i = 0; i < records.size(); i++) {
            SourceRow record = records.get(i);
            if (record.lon() == null) {
                queryOf[i] = AddressRule.query(record);
                if (queryOf[i] != null) {
                    example.putIfAbsent(queryOf[i], record);
                }
            }
        }
        if (example.isEmpty()) {
            return batch;
        }
        Map<String, Geocode> decided = new HashMap<>(geocoding.cache().lookup(example.keySet()));
        int cached = decided.size();
        int failed = 0;
        var fresh = new HashMap<String, Geocode>();
        List<String> misses = example.keySet().stream().filter(q -> !decided.containsKey(q)).toList();
        if (!misses.isEmpty()) {
            try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
                var answers = new LinkedHashMap<String, Future<List<Candidate>>>();
                for (String query : misses) {
                    answers.put(query, calls.submit(() -> geocoding.search(query)));
                }
                for (var answer : answers.entrySet()) {
                    try {
                        fresh.put(answer.getKey(), AddressRule.decide(example.get(answer.getKey()), answer.getValue().get(),
                                area));
                    } catch (ExecutionException e) {
                        if (e.getCause() instanceof InterruptedException) {
                            throw new InterruptedException("geocoding interrupted");
                        }
                        failed++;
                        // Never the query: an address is not for logs.
                        log.warn("ingest {}: a geocode call failed ({}); its records stay unplaced until the next run",
                                source, e.getCause().toString());
                    }
                }
            }
            geocoding.cache().store(fresh);
            decided.putAll(geocoding.cache().lookup(fresh.keySet()));
        }

        var placed = new ArrayList<SourceRow>(records.size());
        int accepted = 0;
        for (int i = 0; i < records.size(); i++) {
            Geocode geocode = queryOf[i] == null ? null : decided.get(queryOf[i]);
            if (geocode == null) {
                placed.add(records.get(i));
            } else {
                placed.add(records.get(i).geocoded(geocode.lon(), geocode.lat(), geocode.quality()));
                accepted += geocode.located() ? 1 : 0;
            }
        }
        metrics.geocoded(cached, misses.size() - failed, failed, accepted);
        return new ParsedBatch(batch.seq(), batch.rows(), placed, batch.rejects(), batch.skipped(), batch.inspections(),
                batch.checksum());
    }
}
