package io.github.naveen11.placemap.ingest.source;

import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The Boston Public Library's 26 locations. An enrichment source for libraries: Overture and OSM already
 * have them, so these records are matched to those places, never create them. The branch name ("West End") alone
 * would barely resemble "West End Branch, Boston Public Library", so the record is named the way the library names
 * its branches.
 */
@Component
public class BostonPublicLibraries extends CkanCsvSource {

    public static final String ID = "boston_public_libraries";

    private static final List<String> COLUMNS = List.of("BRANCH", "ST_ADDRESS", "ZIPCODE", "DISTRICT", "POINT_X", "POINT_Y");

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
        return List.of("BRANCH");
    }

    @Override
    public String key(CsvRow row) {
        return row.get("BRANCH");
    }

    @Override
    public RowResult parse(CsvRow row, ParseContext context) {
        String branch = key(row);
        if (branch == null) {
            return RowResult.rejected("missing BRANCH");
        }
        var point = context.readPoint(row.get("POINT_X"), row.get("POINT_Y"));
        if (point.failure() != null) {
            return point.failure();
        }
        String name = "Central".equals(branch)
                ? "Boston Public Library Central Library"
                : "Boston Public Library " + branch + " Branch";
        String category = context.categories().officialCategory(ID, row::get).orElse(null);
        var attrs = new LinkedHashMap<String, String>();
        attrs.put("BRANCH", branch);
        attrs.put("DISTRICT", row.get("DISTRICT"));
        return RowResult.accepted(SourceRow.of(branch, name, category, point.lon(), point.lat(), row.get("ST_ADDRESS"),
                row.get("ZIPCODE"), null, null, attrs));
    }
}
