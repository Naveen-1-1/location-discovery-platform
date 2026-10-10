package io.github.naveen11.placemap.api.places;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The 13 app categories, cached. Tracing showed every nearby request re-reading this table, which doubled its database
 * round trips (~30 ms each to Neon). Categories only change through migrations; a 10-minute refresh picks those up
 * without a restart, and refreshes happen in the background while callers keep getting the current list.
 */
@Component
public class CategoryCatalog {

    private record Snapshot(List<Category> all, Set<String> codes) {}

    private final LoadingCache<Boolean, Snapshot> cache;

    public CategoryCatalog(PlaceRepository places) {
        this.cache = Caffeine.newBuilder()
                .refreshAfterWrite(Duration.ofMinutes(10))
                .build(key -> {
                    var all = places.categories();
                    return new Snapshot(all, all.stream().map(Category::code).collect(Collectors.toUnmodifiableSet()));
                });
    }

    public List<Category> all() {
        return cache.get(Boolean.TRUE).all();
    }

    public boolean isKnown(String code) {
        return cache.get(Boolean.TRUE).codes().contains(code);
    }
}
