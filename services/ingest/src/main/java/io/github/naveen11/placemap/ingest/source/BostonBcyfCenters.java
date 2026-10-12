package io.github.naveen11.placemap.ingest.source;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Boston Centers for Youth &amp; Families sites (35 rows on 2026-10-11). An existence source for community centres,
 * alongside Overture and OSM.
 *
 * <p>The list is hand-kept and needs some repair: sites are short names ("Pino") and get the centres' public names
 * ("BCYF Pino Community Center"); the trailing "*" some sites carry is dropped from the name; outdoor pools are
 * listed too and are skipped, because a pool is not a community centre; ZIP codes arrive as numbers
 * ("2128.000000000000000") and phone numbers without the 617 area code.
 */
@Component
public class BostonBcyfCenters extends CkanCsvSource {

    public static final String ID = "boston_bcyf_community_centers";

    private static final List<String> COLUMNS = List.of("SITE", "STREET", "NEIGH", "ZIP", "PHONE", "POINT_X", "POINT_Y");
    private static final Pattern LOCAL_PHONE = Pattern.compile("\\d{3}-\\d{4}");

    @Override
    public String sourceId() {
        return ID;
    }

    @Override
    public List<String> columns() {
        return COLUMNS;
    }

    @Override
    protected List<String> sortColumns() {
        return List.of("SITE");
    }

    @Override
    public String key(CsvRow row) {
        return row.get("SITE");
    }

    @Override
    public RowResult parse(CsvRow row, ParseContext context) {
        String site = key(row);
        if (site == null) {
            return RowResult.rejected("missing SITE");
        }
        String bare = site.replaceFirst("\\*+$", "").strip();
        if (bare.endsWith(" Pool")) {
            return RowResult.skipped("a pool, not a community centre");
        }
        var point = context.readPoint(row.get("POINT_X"), row.get("POINT_Y"));
        if (point.failure() != null) {
            return point.failure();
        }
        String category = context.categories().officialCategory(ID, row::get).orElse(null);
        var attrs = new LinkedHashMap<String, String>();
        attrs.put("SITE", site);
        attrs.put("NEIGH", row.get("NEIGH"));
        return RowResult.accepted(SourceRow.of(site, publicName(bare), category, point.lon(), point.lat(),
                row.get("STREET"), zip(row.get("ZIP")), phone(row.get("PHONE")), null, attrs));
    }

    static String publicName(String site) {
        if (site.startsWith("BCYF ")) {
            return site;
        }
        return site.endsWith("Center") ? "BCYF " + site : "BCYF " + site + " Community Center";
    }

    /** "2128.000000000000000" → "02128". */
    static String zip(String value) {
        if (value == null) {
            return null;
        }
        try {
            return String.format("%05d", (long) Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return value;
        }
    }

    /** "635-5120" → "617-635-5120"; anything else as given. */
    static String phone(String value) {
        return value != null && LOCAL_PHONE.matcher(value).matches() ? "617-" + value : value;
    }
}
