package io.github.naveen11.placemap.ingest.source;

import io.github.naveen11.placemap.ingest.catalog.CoverageArea;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Stores that accept SNAP (EBT), from the USDA's retailer locator (991 in the area's bounding box on 2026-10-11). It
 * adds the "Accepts SNAP/EBT" mark to places it matches and, for groceries, may create a store the open sources
 * miss. Bakeries, meat markets and other specialty stores carry no app category but still match, so
 * their places get the mark too. CC BY 4.0: credited in the map's attribution and the data release.
 */
@Component
public class UsdaSnapRetailers extends ArcgisFeatureSource {

    public static final String ID = "usda_snap_retailers";

    private static final List<String> FIELDS = List.of(
            "Record_ID", "Store_Name", "Store_Street_Address", "Additonal_Address", "City", "State", "Zip_Code",
            "Store_Type", "Incentive_Program");

    public UsdaSnapRetailers(CoverageArea area) {
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
    protected String where() {
        return "State='MA'";
    }

    @Override
    public RowResult parse(CsvRow row, ParseContext context) {
        String key = key(row);
        if (key == null) {
            return RowResult.rejected("missing Record_ID");
        }
        String name = row.get("Store_Name");
        if (name == null) {
            return RowResult.rejected("missing Store_Name");
        }
        var point = context.readPoint(row.get(LON), row.get(LAT));
        if (point.failure() != null) {
            return point.failure();
        }
        String category = context.categories().officialCategory(ID, row::get).orElse(null);
        var attrs = new LinkedHashMap<String, String>();
        // The unit stays out of the address, where it would lower the street similarity in matching.
        for (String field : List.of("Additonal_Address", "City", "Store_Type", "Incentive_Program")) { // sic
            attrs.put(field, row.get(field));
        }
        return RowResult.accepted(SourceRow.of(key, name, category, point.lon(), point.lat(),
                row.get("Store_Street_Address"), row.get("Zip_Code"), null, null, attrs));
    }
}
