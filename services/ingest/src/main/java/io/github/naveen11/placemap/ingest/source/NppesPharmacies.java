package io.github.naveen11.placemap.ingest.source;

import io.github.naveen11.placemap.ingest.catalog.AreaPostcodes;
import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pharmacies (organisations, NPI-2) from the CMS NPPES registry: an enrichment source that also corroborates Overture's
 * pharmacies, about a fifth of which are drug companies. The registry has no coordinates, so records are
 * placed by the geocode stage from their practice address.
 *
 * <p>The API returns at most 200 records a request and 1,200 a search, so the fetch searches each of the four towns'
 * ZIP codes ({@code data/area.yaml}). A record can turn up under two codes (its mailing address matches too); it is
 * kept once. Only the practice ({@code LOCATION}) address is kept, and the authorised official's name and phone, which
 * every record carries, are never written anywhere.
 */
@Component
public class NppesPharmacies implements SourceAdapter {

    public static final String ID = "nppes_npi_pharmacies";

    private static final List<String> COLUMNS = List.of(
            "number", "organization_name", "dba_name", "taxonomy_code", "taxonomy_desc", "address_1", "address_2",
            "city", "state", "postal_code", "telephone_number", "status", "last_updated");
    private static final int PAGE = 200;
    private static final int MAX_SKIP = 1000;
    private static final String DOING_BUSINESS_AS = "3";

    private static final JsonMapper JSON = new JsonMapper();

    private final AreaPostcodes postcodes;

    public NppesPharmacies(AreaPostcodes postcodes) {
        this.postcodes = postcodes;
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
    public boolean geocodesMissingLocations() {
        return true;
    }

    @Override
    public void fetch(SourceDefinition source, Path target, HttpDownloader http) throws IOException, InterruptedException {
        if (!"rest_api".equals(source.access().method())) {
            throw new IllegalStateException(ID + " is registered as " + source.access().method() + ", not rest_api");
        }
        var byNumber = new TreeMap<String, List<String>>();
        for (String zip : postcodes.codes()) {
            for (int skip = 0; ; skip += PAGE) {
                URI page = URI.create(source.access().url() + "&taxonomy_description=Pharmacy&enumeration_type=NPI-2"
                        + "&postal_code=" + URLEncoder.encode(zip + "*", StandardCharsets.UTF_8)
                        + "&limit=" + PAGE + "&skip=" + skip);
                List<JsonNode> results = results(http.getText(page), zip);
                for (JsonNode result : results) {
                    List<String> row = row(result);
                    byNumber.putIfAbsent(row.getFirst(), row);
                }
                if (results.size() < PAGE) {
                    break;
                }
                if (skip + PAGE > MAX_SKIP) {
                    throw new IOException("NPPES has more than " + (MAX_SKIP + PAGE) + " pharmacies under ZIP " + zip
                            + ", more than one search returns");
                }
            }
        }
        SnapshotFiles.writeSorted(target, COLUMNS, List.copyOf(byNumber.values()));
    }

    private static List<JsonNode> results(String answer, String zip) throws IOException {
        JsonNode root = JSON.readTree(answer);
        if (root.has("Errors")) {
            throw new IOException("NPPES refused the search for ZIP " + zip + ": " + root.get("Errors"));
        }
        return root.path("results").values().stream().toList();
    }

    /** One record's kept fields; everything about the authorised official stays behind. */
    static List<String> row(JsonNode result) {
        JsonNode basic = result.path("basic");
        String dba = result.path("other_names").values().stream()
                .filter(n -> DOING_BUSINESS_AS.equals(n.path("code").asString("")))
                .map(n -> n.path("organization_name").asString(""))
                .findFirst().orElse("");
        List<JsonNode> taxonomies = result.path("taxonomies").values().stream().toList();
        JsonNode taxonomy = taxonomies.stream().filter(t -> t.path("primary").asBoolean(false)).findFirst()
                .orElse(taxonomies.isEmpty() ? JSON.createObjectNode() : taxonomies.getFirst());
        JsonNode location = result.path("addresses").values().stream()
                .filter(a -> "LOCATION".equals(a.path("address_purpose").asString("")))
                .findFirst().orElse(JSON.createObjectNode());
        String postal = location.path("postal_code").asString("");
        var row = new ArrayList<String>(COLUMNS.size());
        row.add(result.path("number").asString(""));
        row.add(basic.path("organization_name").asString(""));
        row.add(dba);
        row.add(taxonomy.path("code").asString(""));
        row.add(taxonomy.path("desc").asString(""));
        row.add(location.path("address_1").asString(""));
        row.add(location.path("address_2").asString(""));
        row.add(location.path("city").asString(""));
        row.add(location.path("state").asString(""));
        row.add(postal.length() >= 5 ? postal.substring(0, 5) : postal);
        row.add(location.path("telephone_number").asString(""));
        row.add(basic.path("status").asString(""));
        row.add(basic.path("last_updated").asString(""));
        return row;
    }

    @Override
    public String key(CsvRow row) {
        return row.get("number");
    }

    @Override
    public RowResult parse(CsvRow row, ParseContext context) {
        String key = key(row);
        if (key == null) {
            return RowResult.rejected("missing NPI number");
        }
        if (!"A".equals(row.get("status"))) {
            return RowResult.skipped("deactivated NPI"); // CMS: show only the number and date for these
        }
        if (!"MA".equals(row.get("state")) || !postcodes.contains(row.get("postal_code"))) {
            return RowResult.skipped("practice address outside the four towns' ZIP codes");
        }
        String name = Optional.ofNullable(row.get("dba_name")).orElse(row.get("organization_name"));
        if (name == null) {
            return RowResult.rejected("missing organisation name");
        }
        if (row.get("address_1") == null) {
            return RowResult.rejected("missing practice address");
        }
        String category = context.categories().officialCategory(ID, row::get).orElse(null);
        var attrs = new LinkedHashMap<String, String>();
        for (String column : List.of("organization_name", "dba_name", "taxonomy_code", "taxonomy_desc", "address_2",
                "city", "last_updated")) {
            attrs.put(column, row.get(column));
        }
        return RowResult.accepted(SourceRow.of(key, name, category, null, null, row.get("address_1"),
                row.get("postal_code"), row.get("telephone_number"), null, attrs));
    }
}
