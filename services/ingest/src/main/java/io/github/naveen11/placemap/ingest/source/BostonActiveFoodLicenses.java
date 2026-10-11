package io.github.naveen11.placemap.ingest.source;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Boston's Active Food Establishment Licenses (about 3,400 rows, daily). An enrichment source for food places
 * (ADR 0032): records are matched to places, never create them. The phone column is never requested (ADR 0031).
 *
 * <p>The dataset has no licence number. (property_id, business name, licence category, address) is unique; checked
 * against the full dataset on 2026-10-10, including the ~300 rows without a property_id.
 */
@Component
public class BostonActiveFoodLicenses extends CkanCsvSource {

    public static final String ID = "boston_active_food_licenses";

    private static final List<String> COLUMNS = List.of(
            "businessname", "dbaname", "address", "city", "state", "zip", "licstatus", "licensecat", "descript",
            "license_add_dt_tm", "property_id", "latitude", "longitude");

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
        return List.of("property_id", "businessname", "licensecat", "address");
    }

    @Override
    public boolean geocodesMissingLocations() {
        return true;
    }

    @Override
    public String key(CsvRow row) {
        String business = row.get("businessname");
        String category = row.get("licensecat");
        String address = row.get("address");
        if (business == null || category == null || address == null) {
            return null;
        }
        String property = Optional.ofNullable(row.get("property_id")).orElse("");
        return String.join("|", property, business, category, address);
    }

    @Override
    public RowResult parse(CsvRow row, ParseContext context) {
        String key = key(row);
        if (key == null) {
            // Seen on 2026-10-10: five harbour vendors (boats, an island stand) with no address and no coordinates.
            // They cannot be placed on the map or matched, so they stay in the reject table.
            String missing = row.get("businessname") == null ? "business name"
                    : row.get("licensecat") == null ? "licence category" : "address";
            return RowResult.rejected("missing " + missing);
        }
        String latText = row.get("latitude");
        String lonText = row.get("longitude");
        Double lat = null;
        Double lon = null;
        if (latText != null || lonText != null) {
            if (latText == null || lonText == null) {
                return RowResult.rejected("only one of latitude and longitude");
            }
            try {
                lat = Double.valueOf(latText);
                lon = Double.valueOf(lonText);
            } catch (NumberFormatException e) {
                return RowResult.rejected("unreadable coordinates");
            }
            Optional<String> problem = context.pointProblem(lon, lat);
            if (problem.isPresent()) {
                return RowResult.rejected(problem.get());
            }
        }
        // About 6% of rows have no coordinates. They are kept without a location rather than rejected, because
        // rejecting them would trip the 2% limit every run; the geocode stage places them from their address.

        // The trading name is what appears on the door (and in Overture/OSM); the legal name is kept in attrs.
        String name = Optional.ofNullable(row.get("dbaname")).orElse(row.get("businessname"));
        String category = context.categories().officialCategory(ID, row::get).orElse(null);

        var attrs = new LinkedHashMap<String, String>();
        for (String column : List.of("businessname", "dbaname", "city", "state", "licstatus", "licensecat", "descript",
                "license_add_dt_tm", "property_id")) {
            attrs.put(column, row.get(column));
        }
        return RowResult.accepted(SourceRow.of(key, name, category, lon, lat, collapseSpaces(row.get("address")),
                row.get("zip"), null, null, attrs));
    }

    private static String collapseSpaces(String value) {
        return value == null ? null : value.replaceAll("\\s+", " ");
    }
}
