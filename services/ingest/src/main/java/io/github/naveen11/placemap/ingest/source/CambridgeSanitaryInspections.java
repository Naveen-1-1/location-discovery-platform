package io.github.naveen11.placemap.ingest.source;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Cambridge's Sanitary Inspections since 2021: about 12,000 rows, one per violation line, grouped into inspection
 * cases. The data names no permit number, so the establishment is identified by business name and address.
 * Each establishment's newest line describes its record, an enrichment for food places.
 *
 * <p>Cambridge lists only the violations it found, so a line with a code is recorded as a failed check (Boston
 * instead lists every item checked, marked Pass or Fail).
 *
 * <p>Two source defects, seen 2026-10-11: 1,467 lines of complete cases lack the start date but carry the case's
 * submission date, which every line of a case shares, so it stands in; 782 lines have no business, no date and no
 * location at all, so they cannot be attributed and are skipped.
 */
@Component
public class CambridgeSanitaryInspections extends SocrataCsvSource {

    public static final String ID = "cambridge_sanitary_inspections";

    private static final List<String> COLUMNS = List.of(
            "id", "business_name", "full_address", "mbl", "latitude", "longitude", "case_open_date", "applicant_submit_date",
            "case_closed_date", "status", "code_number", "code_description", "date_corrected", "inpector_comments");

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
        return Optional.ofNullable(inspectionDate(row)).orElse("");
    }

    private static String inspectionDate(CsvRow row) {
        return Optional.ofNullable(row.get("case_open_date")).orElse(row.get("applicant_submit_date"));
    }

    @Override
    public Optional<InspectionLine> inspection(CsvRow row) {
        String key = key(row);
        if (key == null) {
            return Optional.empty(); // an unattributable line; parse() skips it
        }
        if (inspectionDate(row) == null) {
            throw new IllegalArgumentException("missing inspection date");
        }
        InspectionLine.Violation violation = null;
        if (row.get("code_number") != null) {
            violation = new InspectionLine.Violation(row.get("code_number"), row.get("code_description"), null, "Fail",
                    Timestamps.parse(inspectionDate(row)), Timestamps.parse(row.get("date_corrected")),
                    row.get("inpector_comments"));
        }
        return Optional.of(new InspectionLine(key, Timestamps.parse(inspectionDate(row)),
                Optional.ofNullable(row.get("status")).orElse("Open"), violation));
    }

    @Override
    public RowResult parse(CsvRow row, ParseContext context) {
        String key = key(row);
        if (key == null) {
            return RowResult.skipped("violation line with no business, date or location");
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
