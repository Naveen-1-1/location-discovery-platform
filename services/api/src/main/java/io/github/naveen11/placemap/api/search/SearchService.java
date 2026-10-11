package io.github.naveen11.placemap.api.search;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.naveen11.placemap.api.Deadline;
import io.github.naveen11.placemap.api.places.PlaceRepository;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The search box (ADR 0008, 0042): place names from our database and addresses from Photon, asked at the same time.
 *
 * <ul>
 *   <li>Names go through the database bulkhead that nearby uses, so a burst of typing cannot starve map searches.
 *   <li>Addresses go through their own bulkhead (four calls at once), and an answer is shared for ten minutes by
 *       everyone typing the same text near the same place, which keeps us within public Photon's fair use. A failed
 *       call is not cached.
 *   <li>Photon is cut off at the search's deadline: the box then shows the places alone and says addresses are
 *       unavailable, rather than waiting.
 * </ul>
 */
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    /** Fewer letters than this match too much: names need two, addresses three. */
    static final int MIN_NAME_LENGTH = 2;
    static final int MIN_ADDRESS_LENGTH = 3;

    /** What the search box shows. {@code addressStatus}: ok, too_short, busy, timeout or unavailable. */
    public record SearchResult(List<PlaceRepository.NameMatch> places, List<AddressMatch> addresses, String addressStatus) {
    }

    /** The cache key: the text as sent, and the location hint as sent (about a kilometre). */
    record AddressKey(String query, String lat, String lon) {
    }

    private final PlaceRepository places;
    private final PhotonClient photon;
    private final Bulkhead database;
    private final Bulkhead photonCalls;
    private final SearchProperties properties;
    private final MeterRegistry registry;
    private final ExecutorService photonThreads =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("search-photon-", 1).factory());
    private final AsyncCache<AddressKey, List<AddressMatch>> addresses;

    public SearchService(PlaceRepository places, PhotonClient photon, Bulkhead database, Bulkhead photonCalls,
            SearchProperties properties, MeterRegistry registry) {
        this.places = places;
        this.photon = photon;
        this.database = database;
        this.photonCalls = photonCalls;
        this.properties = properties;
        this.registry = registry;
        this.addresses = Caffeine.newBuilder()
                .maximumSize(properties.cacheSize())
                .expireAfterWrite(properties.cacheTtl())
                .executor(photonThreads)
                .recordStats()
                .buildAsync();
        CaffeineCacheMetrics.monitor(registry, addresses, "search_addresses");
    }

    /** "  Café   Vanille " → "Café Vanille". */
    static String normalize(String query) {
        return query.strip().replaceAll("\\s+", " ");
    }

    public SearchResult search(String rawQuery, double lat, double lon, Deadline deadline) {
        String query = normalize(rawQuery);
        CompletableFuture<List<AddressMatch>> addressFuture = null;
        if (query.length() >= MIN_ADDRESS_LENGTH) {
            var key = new AddressKey(query.toLowerCase(Locale.ROOT), String.format(Locale.ROOT, "%.2f", lat),
                    String.format(Locale.ROOT, "%.2f", lon));
            addressFuture = addresses.get(key, (k, executor) -> CompletableFuture.supplyAsync(
                            () -> photonCalls.executeSupplier(() -> photon.search(query, lat, lon,
                                    properties.maxAddresses())), executor))
                    .copy()
                    .orTimeout(Math.min(deadline.remaining().toNanos(), properties.photonTimeout().toNanos()),
                            TimeUnit.NANOSECONDS);
        }
        List<PlaceRepository.NameMatch> found = query.length() < MIN_NAME_LENGTH ? List.of()
                : database.executeSupplier(() -> places.searchNames(query, lat, lon, properties.maxPlaces()));

        if (addressFuture == null) {
            return result(found, List.of(), "too_short");
        }
        try {
            return result(found, addressFuture.get(), "ok");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return result(found, List.of(), "unavailable");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                return result(found, List.of(), "timeout");
            }
            if (cause instanceof BulkheadFullException) {
                return result(found, List.of(), "busy");
            }
            // Never the query in the log: it may be someone's address.
            log.warn("address search failed: {}", cause.getClass().getSimpleName());
            return result(found, List.of(), "unavailable");
        }
    }

    private SearchResult result(List<PlaceRepository.NameMatch> found, List<AddressMatch> matches, String status) {
        registry.counter("placemap.search.addresses", "status", status).increment();
        return new SearchResult(found, matches, status);
    }
}
