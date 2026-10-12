package io.github.naveen11.placemap.ingest.source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Cambridge's Food Establishment Permits (about 7,000 rows, daily). An enrichment source for food places,
 * notable for opening hours and seat counts. Each establishment ({@code id}) has one row per yearly renewal, so rows
 * are lines of one record and the newest renewal describes it. The applicant's name is never requested.
 */
@Component
public class CambridgeFoodPermits extends SocrataCsvSource {

    public static final String ID = "cambridge_food_establishment_permits";

    private static final List<String> HOURS = List.of("monday_hours", "tuesday_hours", "wednesday_hours",
            "thursday_hours", "friday_hours", "saturday_hours", "sunday_hours");
    private static final List<String> COLUMNS;

    static {
        var columns = new ArrayList<>(List.of("id", "viewpoint_id", "business_name", "dba", "full_address",
                "latitude", "longitude", "status", "issue_date", "permit_type", "food_est_permit_type",
                "number_of_seats", "mbl"));
        columns.addAll(HOURS);
        COLUMNS = List.copyOf(columns);
    }

    @Override
    public String sourceId() {
        return ID;
    }

    @Override
    public List<String> columns() {
        return COLUMNS;
    }

    @Override
    protected List<String> orderColumns() {
        return List.of("id", "issue_date", "viewpoint_id");
    }

    @Override
    public String key(CsvRow row) {
        return row.get("id");
    }

    @Override
    public boolean linesPerRecord() {
        return true;
    }

    @Override
    public String recordVersion(CsvRow row) {
        return Optional.ofNullable(row.get("issue_date")).orElse("");
    }

    @Override
    public RowResult parse(CsvRow row, ParseContext context) {
        String key = key(row);
        if (key == null) {
            return RowResult.rejected("missing establishment id");
        }
        String name = Optional.ofNullable(row.get("dba")).orElse(row.get("business_name"));
        if (name == null) {
            return RowResult.rejected("missing business name");
        }
        Double lat = null;
        Double lon = null;
        if (row.get("latitude") != null && row.get("longitude") != null) {
            try {
                lat = Double.valueOf(row.get("latitude"));
                lon = Double.valueOf(row.get("longitude"));
            } catch (NumberFormatException e) {
                return RowResult.rejected("unreadable coordinates");
            }
            Optional<String> problem = context.pointProblem(lon, lat);
            if (problem.isPresent()) {
                return RowResult.rejected(problem.get());
            }
        }
        var attrs = new LinkedHashMap<String, String>();
        for (String column : List.of("business_name", "dba", "status", "issue_date", "permit_type",
                "food_est_permit_type", "number_of_seats", "mbl")) {
            attrs.put(column, row.get(column));
        }
        HOURS.forEach(day -> attrs.put(day, row.get(day)));
        String category = context.categories().officialCategory(ID, row::get).orElse(null);
        String address = row.get("full_address");
        return RowResult.accepted(SourceRow.of(key, name, category, lon, lat, street(address), postcode(address), null,
                null, attrs));
    }
}
