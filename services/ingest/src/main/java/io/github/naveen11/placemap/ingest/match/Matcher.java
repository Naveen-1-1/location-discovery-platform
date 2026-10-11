package io.github.naveen11.placemap.ingest.match;

import io.github.naveen11.placemap.ingest.catalog.SourcePriority;
import io.github.naveen11.placemap.ingest.source.SourceRow;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.locationtech.jts.io.WKTReader;

/**
 * Decides, for one record, which place it belongs to (ADR 0022, gate G1 rules from ADR 0032):
 *
 * <ol>
 *   <li>Already linked: keep the place.
 *   <li>A source that describes another source's records (park features of a parcel) joins that record's place by
 *       key, and is never scored.
 *   <li>Score every live place within 100 m whose kind is compatible with the record's (ADR 0042: the same
 *       category, a listed pair such as food and grocery, or a record without a category) as 0.45·name +
 *       0.25·address + 0.2·distance + 0.1·category. The best one at 0.80 or above is a match. Distances to and from an outline (a park) are measured from its edge, 0 m
 *       inside it.
 *   <li>A low-confidence Overture record may also join a nearby place of the same category with a name similarity
 *       of at least 0.85, if another existence source backs that place (corroboration).
 *   <li>Best score 0.60–0.80: review list, record stays unlinked.
 *   <li>Otherwise a new place, if this source may create one in this category; else unlinked.
 * </ol>
 *
 * Pure function of the record and the frozen index, so it is safe on any number of threads.
 */
public final class Matcher {

    public static final double RADIUS_METERS = 100;
    public static final double LINK_THRESHOLD = 0.80;
    public static final double REVIEW_THRESHOLD = 0.60;

    private final PlaceIndex index;
    private final SourcePriority priority;
    private final String source;

    public Matcher(PlaceIndex index, SourcePriority priority, String source) {
        this.index = index;
        this.priority = priority;
        this.source = source;
    }

    public MatchDecision decide(SourceRow record) {
        Long linked = index.linkedPlace(record.key());
        if (linked != null) {
            return new MatchDecision.Keep(linked);
        }
        if (index.joinSource() != null) {
            Long joined = index.joinedPlace(record.key());
            return joined != null
                    ? new MatchDecision.Link(joined, 1.0, "id")
                    : new MatchDecision.Unlinked("no " + index.joinSource() + " place with this key");
        }
        if (record.lon() == null) {
            return new MatchDecision.Unlinked("no location");
        }
        String name = Similarity.normalizeName(record.name());
        Similarity.Address address = Similarity.normalizeAddress(record.address());
        // A park is compared by its outline: an OSM park's point lies inside it, however far from this record's point.
        List<PlaceIndex.Nearby> nearby = record.outline() == null
                ? index.near(record.lon(), record.lat(), RADIUS_METERS)
                : index.nearOutline(PlaceIndex.read(new WKTReader(), record.outline()), RADIUS_METERS);

        PlaceIndex.Nearby best = null;
        double bestScore = -1;
        for (PlaceIndex.Nearby candidate : nearby) { // nearest first, so ties go to the nearer, then lower id
            if (!priority.compatible(record.category(), candidate.place().category())) {
                continue; // a hospital is not its own medical library, however alike the names
            }
            double score = score(record, name, address, candidate);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best != null && bestScore >= LINK_THRESHOLD) {
            return new MatchDecision.Link(best.place().placeId(), round(bestScore), "score");
        }

        Double confidence = confidence(record);
        if (priority.needsCorroboration(record.category(), source, confidence)) {
            Set<String> corroborators = priority.corroborators(record.category(), source);
            for (PlaceIndex.Nearby candidate : nearby) {
                PlaceIndex.IndexedPlace place = candidate.place();
                if (candidate.meters() <= priority.corroborationRadiusMeters()
                        && Objects.equals(place.category(), record.category())
                        && Similarity.names(name, place.normalizedName()) >= priority.minCorroborationNameSimilarity()
                        && place.sources().stream().anyMatch(corroborators::contains)) {
                    return new MatchDecision.Link(place.placeId(), round(score(record, name, address, candidate)),
                            "corroborated");
                }
            }
        }
        if (best != null && bestScore >= REVIEW_THRESHOLD) {
            return new MatchDecision.Review(best.place().placeId(), round(bestScore));
        }
        if (priority.mayCreateAlone(record.category(), source, confidence)) {
            return new MatchDecision.Create();
        }
        return new MatchDecision.Unlinked(priority.needsCorroboration(record.category(), source, confidence)
                ? "awaiting corroboration" : "this source does not create places in this category");
    }

    static double score(SourceRow record, String normalizedName, Similarity.Address address, PlaceIndex.Nearby candidate) {
        PlaceIndex.IndexedPlace place = candidate.place();
        double name = Similarity.names(normalizedName, place.normalizedName());
        double street = Similarity.addresses(address, place.address());
        double distance = Math.max(0, 1 - candidate.meters() / RADIUS_METERS);
        double category = Objects.equals(record.category(), place.category()) ? 1 : 0;
        return 0.45 * name + 0.25 * street + 0.2 * distance + 0.1 * category;
    }

    private static Double confidence(SourceRow record) {
        String value = record.attrs().get("confidence");
        return value == null ? null : Double.valueOf(value);
    }

    /** Four decimals: enough to read, and stable when stored as real. */
    private static double round(double score) {
        return Math.round(score * 10_000) / 10_000.0;
    }
}
