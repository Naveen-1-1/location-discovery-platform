package io.github.naveen11.placemap.ingest.match;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;

/**
 * Name and address comparison for matching. Names are compared with Jaro-Winkler after normalising case,
 * accents and punctuation. Addresses are compared as house number plus street name, ignoring the street type,
 * because sources disagree on it: Boston licences say "100 Federal", Overture "100 Federal St", OSM "100 Federal
 * Street".
 */
public final class Similarity {

    /** Neutral score when either side has no address: it neither supports nor contradicts a match. */
    static final double UNKNOWN_ADDRESS = 0.5;

    private static final JaroWinklerSimilarity JARO_WINKLER = new JaroWinklerSimilarity();
    private static final Set<String> STREET_TYPES = Set.of(
            "st", "street", "ave", "av", "avenue", "rd", "road", "blvd", "boulevard", "sq", "square", "pl", "place",
            "dr", "drive", "ln", "lane", "ct", "court", "pkwy", "parkway", "hwy", "highway", "ter", "terrace", "way",
            "wy", "cir", "circle", "row", "wharf", "plz", "plaza");
    private static final Map<String, String> DIRECTIONS = Map.of(
            "north", "n", "south", "s", "east", "e", "west", "w");

    private Similarity() {
    }

    /** Lower case, no accents, "&" as "and", other punctuation as spaces, no leading "the". */
    public static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        String s = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        s = s.replace("&", " and ").replaceAll("['’`]", "").replaceAll("[^a-z0-9]+", " ").strip();
        return s.startsWith("the ") ? s.substring(4) : s;
    }

    /** Jaro-Winkler similarity of two names already passed through {@link #normalizeName}. */
    public static double names(String normalizedA, String normalizedB) {
        if (normalizedA.isEmpty() || normalizedB.isEmpty()) {
            return 0;
        }
        return JARO_WINKLER.apply(normalizedA, normalizedB);
    }

    /** House number and street name tokens; street types dropped, directions shortened. */
    public record Address(String number, String street) {
    }

    public static Address normalizeAddress(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String s = Normalizer.normalize(address, Normalizer.Form.NFKD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>(List.of(s.replaceAll("[^a-z0-9-]+", " ").strip().split(" +")));
        String number = null;
        if (!tokens.isEmpty() && tokens.getFirst().matches("\\d+[a-z]?(-\\d+[a-z]?)?")) {
            number = tokens.removeFirst().replaceFirst("-.*", ""); // "1-3" → "1": ranges start at the first number
        }
        List<String> street = tokens.stream()
                .filter(t -> !t.isEmpty() && !STREET_TYPES.contains(t))
                .map(t -> DIRECTIONS.getOrDefault(t, t))
                .toList();
        return new Address(number, String.join(" ", street));
    }

    /**
     * 1.0 for the same number on the same street. A different house number on the same street scores low, because it
     * is usually a different building; an unknown number counts as most of the street score.
     */
    public static double addresses(Address a, Address b) {
        if (a == null || b == null || a.street().isEmpty() || b.street().isEmpty()) {
            return UNKNOWN_ADDRESS;
        }
        double street = JARO_WINKLER.apply(a.street(), b.street());
        if (a.number() == null || b.number() == null) {
            return 0.8 * street;
        }
        return a.number().equals(b.number()) ? 0.4 + 0.6 * street : 0.3 * street;
    }
}
