package io.github.naveen11.placemap.ingest.geocode;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** The cache's contract without a database: the first decision stored for a query wins. Counts lookups and stores. */
public final class InMemoryGeocodeCache implements GeocodeCache {

    public final Map<String, Geocode> stored = new ConcurrentHashMap<>();
    public final AtomicInteger stores = new AtomicInteger();

    @Override
    public Map<String, Geocode> lookup(Collection<String> queries) {
        var found = new HashMap<String, Geocode>();
        queries.forEach(q -> {
            Geocode g = stored.get(q);
            if (g != null) {
                found.put(q, g);
            }
        });
        return found;
    }

    @Override
    public void store(Map<String, Geocode> decisions) {
        stores.incrementAndGet();
        decisions.forEach(stored::putIfAbsent);
    }
}
