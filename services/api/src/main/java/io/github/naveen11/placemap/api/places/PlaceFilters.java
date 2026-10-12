package io.github.naveen11.placemap.api.places;

/**
 * The filters of a nearby search, part of the key its result is shared under.
 *
 * @param openAt minute of the week (Boston time, since Monday 00:00) the places must be open at, rounded down to five
 *     minutes so that searches a few seconds apart share a result; null when not filtering on hours. Places whose hours
 *     are unknown are left out while it is set (owner decision).
 */
public record PlaceFilters(boolean acceptsSnap, boolean licensed, boolean passedInspection, Integer openAt) {

    public static final PlaceFilters NONE = new PlaceFilters(false, false, false, null);

    /** The bucket size of {@code openAt}: a shared answer is at most this many minutes behind. */
    public static final int OPEN_AT_STEP_MINUTES = 5;

    public boolean any() {
        return acceptsSnap || licensed || passedInspection || openAt != null;
    }

    /** "snap,open" for logs and metrics: which filters are on, never their values. */
    public String names() {
        var names = new StringBuilder();
        if (acceptsSnap) {
            names.append(",snap");
        }
        if (licensed) {
            names.append(",licensed");
        }
        if (passedInspection) {
            names.append(",inspection");
        }
        if (openAt != null) {
            names.append(",open");
        }
        return names.isEmpty() ? "none" : names.substring(1);
    }
}
