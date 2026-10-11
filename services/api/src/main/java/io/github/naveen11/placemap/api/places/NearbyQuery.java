package io.github.naveen11.placemap.api.places;

import io.github.naveen11.placemap.api.routing.TravelMode;
import java.util.List;

/**
 * Everything a nearby result depends on, and so the key under which it is shared (ADR 0025): two requests from the
 * same cell with the same mode, categories and radius get the same answer.
 *
 * @param mode null for a straight-line search
 * @param categories sorted and without repeats, so the order a client lists them in does not matter
 */
public record NearbyQuery(SnappedOrigin origin, TravelMode mode, List<String> categories, int radiusMeters) {

    public NearbyQuery {
        categories = categories.stream().distinct().sorted().toList();
    }
}
