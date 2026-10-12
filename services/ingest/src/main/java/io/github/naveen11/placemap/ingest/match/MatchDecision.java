package io.github.naveen11.placemap.ingest.match;

/** What matching decided for one record. */
public sealed interface MatchDecision {

    /** Already linked in an earlier run: keep that place (stable place ids). */
    record Keep(long placeId) implements MatchDecision {
    }

    /** Link to an existing place. {@code method} is "score" or "corroborated". */
    record Link(long placeId, double score, String method) implements MatchDecision {
    }

    /** Borderline (0.60 up to the link threshold): stays unlinked and goes to the review list. */
    record Review(long placeId, double score) implements MatchDecision {
    }

    /** No match, and this source may create a place in the record's category. */
    record Create() implements MatchDecision {
    }

    /** No match, and not allowed to create a place (an enrichment source, or a low-confidence Overture place). */
    record Unlinked(String reason) implements MatchDecision {
    }
}
