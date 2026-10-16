package io.github.naveen11.placemap.api.places;

/**
 * About how many heap bytes a shared nearby result keeps alive, for the cache's memory bound: the result with its key
 * and cache entry, its list, and each place with its strings, boxed travel figures and facts. Assumes compressed
 * references and 8-byte alignment, and counts strings at one byte a character (most names are Latin-1), so a name
 * with other characters is undercounted by its length.
 */
final class ResultSize {

    /** The result, its key (origin, filters, categories), the shared future and the cache's own entry. */
    static final int RESULT = 384;

    /** A place: header, four longs and doubles, eight references. */
    static final int PLACE = 80;

    private static final int BOXED_LONG = 16;
    private static final int FACTS = 32;
    private static final int OPEN_WEEK = 16;
    private static final int RANGE = 24; // int[2]

    private ResultSize() {
    }

    static int bytes(NearbyResult result) {
        long bytes = RESULT + array(result.places().size());
        for (NearbyPlace p : result.places()) {
            bytes += PLACE + string(p.name()) + string(p.category()) + string(p.address()) + string(p.liveKey())
                    + (p.travelSeconds() == null ? 0 : BOXED_LONG) + (p.travelMeters() == null ? 0 : BOXED_LONG)
                    + facts(p.facts());
        }
        return (int) Math.min(Integer.MAX_VALUE, bytes);
    }

    private static long facts(PlaceFacts facts) {
        if (facts == null || facts == PlaceFacts.NONE) {
            return 0; // shared
        }
        long bytes = FACTS + string(facts.inspection());
        if (facts.week() != null) {
            int ranges = facts.week().ranges().size();
            bytes += OPEN_WEEK + 16 + array(ranges) + (long) ranges * RANGE;
        }
        return bytes;
    }

    /** A String and its byte array. */
    static long string(String s) {
        return s == null ? 0 : 24 + align(16 + s.length());
    }

    /** An array of references. */
    private static long array(int length) {
        return align(16 + 4L * length);
    }

    private static long align(long bytes) {
        return (bytes + 7) & ~7L;
    }
}
