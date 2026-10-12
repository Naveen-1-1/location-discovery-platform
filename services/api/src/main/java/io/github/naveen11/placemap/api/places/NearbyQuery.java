package io.github.naveen11.placemap.api.places;

import io.github.naveen11.placemap.api.routing.TravelMode;
import java.util.List;

/**
 * Everything a nearby result depends on, and so the key under which it is shared: two requests from the
 * same cell with the same mode, categories, radius and filters get the same answer.
 *
 * @param mode null for a straight-line search
 * @param categories sorted and without repeats, so the order a client lists them in does not matter
 */
public record NearbyQuery(SnappedOrigin origin, TravelMode mode, List<String> categories, int radiusMeters,
        PlaceFilters filters) {

    public NearbyQuery {
        categories = categories.stream().distinct().sorted().toList();
    }

    /** Without filters. */
    public NearbyQuery(SnappedOrigin origin, TravelMode mode, List<String> categories, int radiusMeters) {
        this(origin, mode, categories, radiusMeters, PlaceFilters.NONE);
    }
}
