package io.github.naveen11.placemap.ingest.source;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Licences granted by the Boston Licensing Board (3,601 rows on 2026-10-11): restaurants (common victuallers), bars,
 * liquor stores, inns. An enrichment source for food places (ADR 0032), notable for the hours each premises is
 * licensed to open and close. Those are the latest hours allowed, not necessarily when it is open, so they rank below
 * a place's published opening hours. Coordinates are MA State Plane feet (EPSG:2249). The applicant, manager and
 * phone fields are never requested (ADR 0031), nor are the free-text premises descriptions.
 *
 * <p>About 18 licence numbers appear twice as identical rows, so a licence's rows are read as lines of one record.
 */
@Component
public class BostonLicensingBoard extends CkanCsvSource {

    public static final String ID = "boston_licensing_board_licenses";

    private static final List<String> COLUMNS = List.of(
            "license_num", "status", "license_category", "license_type", "expires", "business_name", "dba_name",
            "opening", "closing", "patronsout", "capacity", "address", "city", "state", "zip", "gpsx", "gpsy");
    private static final Set<String> LODGING = Set.of("Dormitory", "Lodging Houses (Frat/Dorm)");
    private static final String CRS = "EPSG:2249";

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
        return List.of("license_num", "license_type", "expires");
    }

    @Override
    public String key(CsvRow row) {
        return row.get("license_num");
    }

    @Override
    public boolean linesPerRecord() {
        return true;
    }

    @Override
    public String recordVersion(CsvRow row) {
        return String.valueOf(row.get("expires"));
    }

    @Override
    public RowResult parse(CsvRow row, ParseContext context) {
        String key = key(row);
        if (key == null) {
            return RowResult.rejected("missing license_num");
        }
        if (LODGING.contains(row.get("license_type"))) {
            return RowResult.skipped("lodging, not a place to visit");
        }
        String name = row.get("dba_name") != null ? row.get("dba_name") : row.get("business_name");
        if (name == null) {
            return RowResult.rejected("missing business name");
        }
        Double lon = null;
        Double lat = null;
        double x;
        double y;
        try {
            // About 140 licences carry 0, 0 for "not located", which would land in the Atlantic off the Carolinas.
            x = row.get("gpsx") == null ? 0 : Double.parseDouble(row.get("gpsx"));
            y = row.get("gpsy") == null ? 0 : Double.parseDouble(row.get("gpsy"));
        } catch (NumberFormatException e) {
            return RowResult.rejected("unreadable coordinates");
        }
        if (x != 0 && y != 0) {
            double[] wgs84 = context.toWgs84(CRS, x, y);
            var point = context.checkPoint(wgs84[0], wgs84[1]);
            if (point.failure() != null) {
                return point.failure();
            }
            lon = point.lon();
            lat = point.lat();
        }
        // About 6% of rows have no coordinates; they are kept without a location, like the food licences, and the
        // geocoder places them.
        String category = context.categories().officialCategory(ID, row::get).orElse(null);
        var attrs = new LinkedHashMap<String, String>();
        for (String column : List.of("status", "license_category", "license_type", "expires", "business_name",
                "dba_name", "opening", "closing", "patronsout", "capacity", "city", "state")) {
            attrs.put(column, row.get(column));
        }
        return RowResult.accepted(SourceRow.of(key, name, category, lon, lat, address(row.get("address")),
                row.get("zip"), null, null, attrs));
    }

    /** "1395-1405  Washington ST" → "1395-1405 Washington ST"; "403A-  Centre ST" (a range never finished) → "403A Centre ST". */
    static String address(String value) {
        return value == null ? null : value.replaceAll("\\s+", " ").replaceFirst("^(\\d+[A-Za-z]?)- ", "$1 ");
    }
}
