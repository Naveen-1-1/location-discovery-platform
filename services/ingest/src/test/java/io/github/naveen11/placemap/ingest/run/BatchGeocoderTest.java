package io.github.naveen11.placemap.ingest.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naveen11.placemap.ingest.TestData;
import io.github.naveen11.placemap.ingest.geocode.Candidate;
import io.github.naveen11.placemap.ingest.geocode.Geocode;
import io.github.naveen11.placemap.ingest.geocode.Geocoder;
import io.github.naveen11.placemap.ingest.geocode.Geocoding;
import io.github.naveen11.placemap.ingest.geocode.InMemoryGeocodeCache;
import io.github.naveen11.placemap.ingest.source.SourceRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/** Geocoding a batch: cache first, at most four calls in flight across partitions, failures left for the next run. */
class BatchGeocoderTest {

    private static final IngestMetrics.RunMetrics METRICS = new IngestMetrics(new SimpleMeterRegistry()).forRun("test");

    /** As the application's {@code geocodeCalls} executor: virtual threads, at most four calls in flight. */
    private static final SimpleAsyncTaskExecutor CALLS = calls();

    private static SimpleAsyncTaskExecutor calls() {
        var calls = new SimpleAsyncTaskExecutor("test-geocode-");
        calls.setVirtualThreads(true);
        calls.setConcurrencyLimit(4);
        return calls;
    }

    /** "<n> Main St, Boston" sits at a point n metres north of Boston Common. */
    private static SourceRow licence(int number) {
        return SourceRow.of("k" + number, "Shop " + number, "food", null, null, number + " Main St", "02108", null, null,
                Map.of("city", "Boston"));
    }

    private static Candidate at(int number) {
        return new Candidate(-71.0656, 42.3550 + number / 111_320.0, Integer.toString(number), "Main Street", "02108",
                number + " Main Street, Boston");
    }

    private static ParsedBatch batch(int seq, List<SourceRow> records) {
        return new ParsedBatch(seq, records.size(), records, List.of(), 0, List.of(), "checksum");
    }

    private static BatchGeocoder geocoder(Geocoding geocoding) {
        return new BatchGeocoder("test", geocoding, CALLS, TestData.area().checker(), METRICS);
    }

    @Test
    void recordsWithoutALocationArePlacedAndTheDecisionIsCached() throws Exception {
        var calls = new AtomicInteger();
        Geocoder photon = query -> {
            calls.incrementAndGet();
            return List.of(at(Integer.parseInt(query.substring(0, query.indexOf(' ')))));
        };
        var cache = new InMemoryGeocodeCache();
        var geocoding = new Geocoding(photon, cache, 1000);
        SourceRow located = SourceRow.of("x", "Placed Already", "food", -71.06, 42.35, "1 Main St", "02108", null, null,
                Map.of());

        ParsedBatch out = geocoder(geocoding).geocode(batch(0, List.of(licence(10), located, licence(20), licence(10))));

        assertThat(calls).as("one call per distinct address").hasValue(2);
        assertThat(out.records().get(0).lat()).isEqualTo(42.3550 + 10 / 111_320.0);
        assertThat(out.records().get(0).attrs()).containsEntry("geocode", Geocode.HOUSE);
        assertThat(out.records().get(1)).isSameAs(located);
        assertThat(out.records().get(3).lat()).isEqualTo(out.records().get(0).lat());
        assertThat(cache.stored).containsKeys("10 main st, boston, ma 02108", "20 main st, boston, ma 02108");

        geocoder(geocoding).geocode(batch(1, List.of(licence(10), licence(20))));
        assertThat(calls).as("the second batch is answered from the cache").hasValue(2);
    }

    @Test
    void anAddressNoAnswerFitsIsMarkedAndCachedSoItIsNotAskedAgain() throws Exception {
        var calls = new AtomicInteger();
        var geocoding = new Geocoding(query -> {
            calls.incrementAndGet();
            return List.of(at(99)); // a different house number
        }, new InMemoryGeocodeCache(), 1000);

        SourceRow out = geocoder(geocoding).geocode(batch(0, List.of(licence(10)))).records().getFirst();
        geocoder(geocoding).geocode(batch(1, List.of(licence(10))));

        assertThat(out.lon()).isNull();
        assertThat(out.attrs()).containsEntry("geocode", Geocode.NONE);
        assertThat(calls).hasValue(1);
    }

    @Test
    void aFailedCallLeavesTheRecordUnplacedAndUncachedForTheNextRun() throws Exception {
        var cache = new InMemoryGeocodeCache();
        var geocoding = new Geocoding(query -> {
            throw new IOException("Photon photon.komoot.io returned HTTP 503");
        }, cache, 1000);

        SourceRow out = geocoder(geocoding).geocode(batch(0, List.of(licence(10)))).records().getFirst();

        assertThat(out).isEqualTo(licence(10));
        assertThat(cache.stored).isEmpty();
    }

    @Test
    void neverMoreThanFourCallsAreInFlightWhateverTheNumberOfPartitions() throws Exception {
        var inFlight = new AtomicInteger();
        var mostInFlight = new AtomicInteger();
        var threads = ConcurrentHashMap.<Boolean>newKeySet();
        Geocoder slow = query -> {
            int now = inFlight.incrementAndGet();
            mostInFlight.accumulateAndGet(now, Math::max);
            threads.add(Thread.currentThread().isVirtual());
            try {
                Thread.sleep(20);
            } finally {
                inFlight.decrementAndGet();
            }
            return List.of(at(Integer.parseInt(query.substring(0, query.indexOf(' ')))));
        };
        var geocoding = new Geocoding(slow, new InMemoryGeocodeCache(), 1000);
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<ParsedBatch>>();
        try (var workers = Executors.newFixedThreadPool(3)) { // three partitions, 12 addresses each
            for (int w = 0; w < 3; w++) {
                int first = w * 100;
                results.add(workers.submit(() -> {
                    start.await();
                    return geocoder(geocoding).geocode(batch(first, IntStream.range(first, first + 12).map(i -> i + 1)
                            .mapToObj(BatchGeocoderTest::licence).toList()));
                }));
            }
            start.countDown();
            for (Future<ParsedBatch> result : results) {
                assertThat(result.get().records()).allSatisfy(r -> assertThat(r.lon()).isNotNull());
            }
        }

        assertThat(mostInFlight.get()).isBetween(2, 4);
        assertThat(threads).as("calls run on virtual threads").containsOnly(true);
    }

    @Test
    void twoBatchesAskingTheSameAddressAtOnceUseTheDecisionStoredFirst() throws Exception {
        // Photon moves the point between the two calls; whichever is stored first must be the one both batches use.
        var answer = new AtomicInteger();
        var bothAsked = new CountDownLatch(2);
        Geocoder fickle = query -> {
            int n = answer.incrementAndGet();
            bothAsked.countDown();
            try {
                bothAsked.await();
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            return List.of(new Candidate(-71.0656 + n * 0.0001, 42.3550, "10", "Main Street", "02108", "10 Main St"));
        };
        var geocoding = new Geocoding(fickle, new InMemoryGeocodeCache(), 1000);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<ParsedBatch> a = workers.submit(() -> geocoder(geocoding).geocode(batch(0, List.of(licence(10)))));
            Future<ParsedBatch> b = workers.submit(() -> geocoder(geocoding).geocode(batch(1, List.of(licence(10)))));
            assertThat(a.get().records().getFirst().lon()).isEqualTo(b.get().records().getFirst().lon());
        }
    }

    @Test
    void theRateLimitHoldsAcrossPartitions() throws Exception {
        // 50 calls a second: 101 calls need three periods of one second, so at least two seconds.
        var geocoding = new Geocoding(query -> List.of(), new InMemoryGeocodeCache(), 50);
        long start = System.nanoTime();
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<ParsedBatch> a = workers.submit(() -> geocoder(geocoding).geocode(batch(0,
                    IntStream.rangeClosed(1, 50).mapToObj(BatchGeocoderTest::licence).toList())));
            Future<ParsedBatch> b = workers.submit(() -> geocoder(geocoding).geocode(batch(1,
                    IntStream.rangeClosed(51, 101).mapToObj(BatchGeocoderTest::licence).toList())));
            a.get();
            b.get();
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(1900));
    }
}
