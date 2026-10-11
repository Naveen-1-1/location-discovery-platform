package io.github.naveen11.placemap.ingest.source;

import io.github.naveen11.placemap.ingest.catalog.CoverageArea;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Hospitals licensed by the Department of Public Health, from MassGIS (103 statewide on 2026-10-10; 23 in the four
 * towns). The only source that creates hospital places (ADR 0032): Overture's "hospitals" are mostly departments.
 * The layer is EPSG:26986; the server converts to WGS84 for us.
 */
@Component
public class MassgisHospitals extends ArcgisFeatureSource {

    public static final String ID = "massgis_hospitals";

    private static final List<String> FIELDS = List.of(
            "DPH_FACILITY_ID", "FACILITY_NAME", "FACILITY_TYPE", "STREET_ADDRESS", "MUNICIPALITY", "ZIP_CODE", "PHONE",
            "TOTAL_BEDS", "EMERGENCY_DEPT", "TRAUMA_LEVEL_ADULT", "TRAUMA_LEVEL_PEDS", "MAD_ID");

    public MassgisHospitals(CoverageArea area) {
        super(area);
    }

    @Override
    public String sourceId() {
        return ID;
    }

    @Override
    protected List<String> fields() {
        return FIELDS;
    }

    @Override
    public RowResult parse(CsvRow row, ParseContext context) {
        String key = key(row);
        if (key == null) {
            return RowResult.rejected("missing DPH_FACILITY_ID");
        }
        String name = row.get("FACILITY_NAME");
        if (name == null) {
            return RowResult.rejected("missing FACILITY_NAME");
        }
        var point = context.readPoint(row.get(LON), row.get(LAT));
        if (point.failure() != null) {
            return point.failure();
        }
        String category = context.categories().officialCategory(ID, row::get).orElse(null);
        var attrs = new LinkedHashMap<String, String>();
        for (String field : List.of("FACILITY_TYPE", "MUNICIPALITY", "TOTAL_BEDS", "EMERGENCY_DEPT",
                "TRAUMA_LEVEL_ADULT", "TRAUMA_LEVEL_PEDS", "MAD_ID")) {
            attrs.put(field, row.get(field));
        }
        return RowResult.accepted(SourceRow.of(key, name, category, point.lon(), point.lat(), row.get("STREET_ADDRESS"),
                row.get("ZIP_CODE"), row.get("PHONE"), null, attrs));
    }
}
