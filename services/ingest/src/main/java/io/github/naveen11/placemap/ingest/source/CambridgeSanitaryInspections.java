package io.github.naveen11.placemap.ingest.source;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Cambridge's Sanitary Inspections since 2021: about 12,000 rows, one per violation line, grouped into inspection
 * cases (ADR 0034). The data names no permit number, so the establishment is identified by business name and address.
 * Each establishment's newest line describes its record, an enrichment for food places (ADR 0032).
 *
 * <p>Cambridge lists only the violations it found, so a line with a code is recorded as a failed check (Boston
 * instead lists every item checked, marked Pass or Fail).
 */
@Component
public class CambridgeSanitaryInspections extends SocrataCsvSource {

    public static final String ID = "cambridge_sanitary_inspections";

    private static final List<String> COLUMNS = List.of(
            "id", "business_name", "full_address", "mbl", "latitude", "longitude", "case_open_date", "case_closed_date",
            "status", "code_number", "code_description", "date_corrected", "inpector_comments");

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
        return List.of("id", "unique_identifier"); // unique per line; ordering by an unselected column is allowed
    }

    @Override
    public String key(CsvRow row) {
        String business = row.get("business_name");
        return business == null ? null : business + "|" + Optional.ofNullable(row.get("full_address")).orElse("");
    }

    @Override
    public boolean linesPerRecord() {
        return true;
    }

    @Override
    public String recordVersion(CsvRow row) {
        return Optional.ofNullable(row.get("case_open_date")).orElse("");
    }

    @Override
    public Optional<InspectionLine> inspection(CsvRow row) {
        String key = key(row);
        if (key == null) {
            throw new IllegalArgumentException("missing business name");
        }
        if (row.get("case_open_date") == null) {
            throw new IllegalArgumentException("missing inspection date");
        }
        InspectionLine.Violation violation = null;
        if (row.get("code_number") != null) {
            violation = new InspectionLine.Violation(row.get("code_number"), row.get("code_description"), null, "Fail",
                    Timestamps.parse(row.get("case_open_date")), Timestamps.parse(row.get("date_corrected")),
                    row.get("inpector_comments"));
        }
        return Optional.of(new InspectionLine(key, Timestamps.parse(row.get("case_open_date")),
                Optional.ofNullable(row.get("status")).orElse("Open"), violation));
    }

    @Override
    public RowResult parse(CsvRow row, ParseContext context) {
        String key = key(row);
        if (key == null) {
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
        attrs.put("mbl", row.get("mbl"));
        String address = row.get("full_address");
        return RowResult.accepted(SourceRow.of(key, row.get("business_name"), null, lon, lat, street(address),
                postcode(address), null, null, attrs));
    }
}
