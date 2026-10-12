package io.github.naveen11.placemap.ingest.source;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Boston's Food Establishment Inspections: the full history since 2006, about 905,000 rows of one violation line each.
 * Every row is history; each licence's newest line also describes the establishment, which becomes this
 * source's record: an enrichment for food places, and an existence source for active retail groceries.
 * The owner's and the people's names are never requested.
 */
@Component
public class BostonFoodInspections extends CkanCsvSource {

    public static final String ID = "boston_food_inspections";

    private static final List<String> COLUMNS = List.of(
            "businessname", "dbaname", "licenseno", "issdttm", "expdttm", "licstatus", "licensecat", "descript",
            "result", "resultdttm", "violation", "viol_level", "violdesc", "violdttm", "viol_status", "status_date",
            "comments", "address", "city", "state", "zip", "property_id", "location");
    private static final Pattern LOCATION = Pattern.compile("\\(\\s*(-?[0-9.]+)\\s*,\\s*(-?[0-9.]+)\\s*\\)");

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
        return List.of("licenseno", "resultdttm", "result", "violation", "violdttm", "viol_status", "status_date",
                "comments");
    }

    @Override
    public String key(CsvRow row) {
        return row.get("licenseno");
    }

    @Override
    public boolean linesPerRecord() {
        return true;
    }

    @Override
    public String recordVersion(CsvRow row) {
        return Optional.ofNullable(inspectionTime(row)).orElse("");
    }

    /**
     * About 6,400 rows (mostly 2007–2013) have no inspection time; where a violation date is given instead it is the
     * same moment (they agree wherever both are present), so it stands in.
     */
    private static String inspectionTime(CsvRow row) {
        return Optional.ofNullable(row.get("resultdttm")).orElse(row.get("violdttm"));
    }

    @Override
    public Optional<InspectionLine> inspection(CsvRow row) {
        String licence = key(row);
        if (licence == null) {
            throw new IllegalArgumentException("missing licence number");
        }
        if (inspectionTime(row) == null) {
            throw new IllegalArgumentException("missing inspection time");
        }
        InspectionLine.Violation violation = null;
        if (row.get("violation") != null) {
            violation = new InspectionLine.Violation(row.get("violation"), row.get("violdesc"), level(row.get("viol_level")),
                    row.get("viol_status"), Timestamps.parse(row.get("violdttm")), Timestamps.parse(row.get("status_date")),
                    row.get("comments"));
        }
        return Optional.of(new InspectionLine(licence, Timestamps.parse(inspectionTime(row)),
                Optional.ofNullable(row.get("result")).orElse("unknown"), violation));
    }

    @Override
    public RowResult parse(CsvRow row, ParseContext context) {
        String key = key(row);
        if (key == null) {
            return RowResult.rejected("missing licence number");
        }
        String name = Optional.ofNullable(row.get("dbaname")).orElse(row.get("businessname"));
        if (name == null) {
            return RowResult.rejected("missing business name");
        }
        Double lat = null;
        Double lon = null;
        String location = row.get("location");
        if (location != null) {
            Matcher m = LOCATION.matcher(location);
            if (!m.matches()) {
                return RowResult.rejected("unreadable location");
            }
            lat = Double.valueOf(m.group(1));
            lon = Double.valueOf(m.group(2));
            Optional<String> problem = context.pointProblem(lon, lat);
            if (problem.isPresent()) {
                return RowResult.rejected(problem.get());
            }
        }
        var attrs = new LinkedHashMap<String, String>();
        for (String column : List.of("businessname", "dbaname", "licstatus", "licensecat", "descript", "issdttm",
                "expdttm", "property_id", "city")) {
            attrs.put(column, row.get(column));
        }
        String category = context.categories().officialCategory(ID, row::get).orElse(null);
        String address = row.get("address") == null ? null : row.get("address").replaceAll("\\s+", " ");
        return RowResult.accepted(SourceRow.of(key, name, category, lon, lat, address, row.get("zip"), null, null, attrs));
    }

    /** "*", "**", "***" → 1, 2, 3. */
    static Integer level(String stars) {
        if (stars == null) {
            return null;
        }
        int level = stars.strip().length();
        return level >= 1 && level <= 3 && stars.strip().chars().allMatch(c -> c == '*') ? level : null;
    }
}
