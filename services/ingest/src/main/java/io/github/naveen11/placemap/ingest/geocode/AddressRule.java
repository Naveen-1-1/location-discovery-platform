package io.github.naveen11.placemap.ingest.geocode;

import io.github.naveen11.placemap.ingest.catalog.CoverageArea;
import io.github.naveen11.placemap.ingest.match.Similarity;
import io.github.naveen11.placemap.ingest.source.SourceRow;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a record's address becomes a query, and which answer is good enough (plan: "Accept only if house number and
 * postcode match and the point is inside the area"). The street must also agree (address similarity of at least
 * 0.85), because Photon answers a street it does not know with the same number on another one.
 */
public final class AddressRule {

    static final double MIN_ADDRESS_SIMILARITY = 0.85;
    private static final Pattern ZIP = Pattern.compile("^(\\d{5})");

    private AddressRule() {
    }

    /**
     * The query for a record without a location, or null if its address cannot be geocoded at all (no street, no house
     * number or no postcode): such records are not sent to the service.
     */
    public static String query(SourceRow record) {
        Similarity.Address address = Similarity.normalizeAddress(record.address());
        String zip = zip(record.postcode());
        if (address == null || address.number() == null || address.street().isEmpty() || zip == null) {
            return null;
        }
        String city = cityOf(record);
        String text = record.address().strip() + (city == null ? "" : ", " + city) + ", MA " + zip;
        return text.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** The first candidate with the record's house number, postcode and street, inside the four towns. */
    public static Geocode decide(SourceRow record, List<Candidate> candidates, CoverageArea.Checker area) {
        Similarity.Address wanted = Similarity.normalizeAddress(record.address());
        String zip = zip(record.postcode());
        for (Candidate candidate : candidates) {
            if (candidate.houseNumber() == null || candidate.street() == null) {
                continue;
            }
            Similarity.Address found = Similarity.normalizeAddress(candidate.houseNumber() + " " + candidate.street());
            if (found == null || !wanted.number().equals(found.number()) || !zip.equals(zip(candidate.postcode()))
                    || Similarity.addresses(wanted, found) < MIN_ADDRESS_SIMILARITY
                    || !area.covers(candidate.lon(), candidate.lat())) {
                continue;
            }
            return Geocode.house(candidate.lon(), candidate.lat(), candidate.label());
        }
        return Geocode.none(candidates.isEmpty() ? "no answer" : "no answer with the same number, street and postcode");
    }

    /** "02138-4302", "021384302" → "02138". */
    static String zip(String postcode) {
        if (postcode == null) {
            return null;
        }
        Matcher m = ZIP.matcher(postcode.strip());
        return m.find() ? m.group(1) : null;
    }

    /** The town the source names, when it names one ("Boston/" in Boston's licences becomes "Boston"). */
    private static String cityOf(SourceRow record) {
        for (String key : List.of("city", "CITY", "City")) {
            String value = record.attrs().get(key);
            if (value != null) {
                String clean = value.replaceAll("[^A-Za-z .'-]", " ").replaceAll("\\s+", " ").strip();
                return clean.isEmpty() ? null : clean;
            }
        }
        return null;
    }
}
