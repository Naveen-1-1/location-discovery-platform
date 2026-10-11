package io.github.naveen11.placemap.ingest.source;

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
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bank branches from the FDIC BankFind API (1,882 offices in Massachusetts on 2026-10-11, one request). The
 * authoritative existence source for banks (ADR 0032); credit unions come from Overture and OSM. Only full-service
 * branches get the bank category ({@code SERVTYPE} 11 and 12 in {@code data/categories.yaml}); loan, trust and
 * administrative offices are skipped. The service-type code is used rather than its description, which FDIC writes
 * in two different cases.
 */
@Component
public class FdicBankLocations implements SourceAdapter {

    public static final String ID = "fdic_bank_locations";

    private static final List<String> COLUMNS = List.of(
            "UNINUM", "CERT", "NAME", "OFFNAME", "ADDRESS", "CITY", "STALP", "ZIP", "SERVTYPE", "SERVTYPE_DESC",
            "MAINOFF", "LONGITUDE", "LATITUDE");
    private static final int LIMIT = 10_000; // the API's maximum page
    private static final String STATE = "STALP:MA";

    private static final JsonMapper JSON = new JsonMapper();

    @Override
    public String sourceId() {
        return ID;
    }

    @Override
    public List<String> columns() {
        return COLUMNS;
    }

    @Override
    public void fetch(SourceDefinition source, Path target, HttpDownloader http) throws IOException, InterruptedException {
        if (!"rest_api".equals(source.access().method())) {
            throw new IllegalStateException(ID + " is registered as " + source.access().method() + ", not rest_api");
        }
        writeSnapshot(http.getText(URI.create(source.access().url() + "?filters=" + encode(STATE)
                + "&fields=" + encode(String.join(",", COLUMNS)) + "&sort_by=UNINUM&sort_order=ASC"
                + "&limit=" + LIMIT + "&format=json")), target);
    }

    void writeSnapshot(String answer, Path target) throws IOException {
        JsonNode root = JSON.readTree(answer);
        List<JsonNode> locations = root.required("data").values().stream().toList();
        int total = root.required("meta").required("total").asInt();
        if (total != locations.size()) {
            throw new IOException("FDIC returned " + locations.size() + " of " + total + " Massachusetts offices");
        }
        var rows = new ArrayList<List<String>>();
        for (JsonNode location : locations) {
            JsonNode data = location.required("data");
            var row = new ArrayList<String>(COLUMNS.size());
            for (String column : COLUMNS) {
                JsonNode value = data.path(column);
                row.add(value.isMissingNode() || value.isNull() ? "" : value.asString());
            }
            rows.add(row);
        }
        SnapshotFiles.writeSorted(target, COLUMNS, rows);
    }

    /** The name of the search index FDIC rebuilds daily ({@code locations_20261009090008}), read from a 1-row answer. */
    @Override
    public String remoteVersion(SourceDefinition source, HttpDownloader http) throws IOException, InterruptedException {
        JsonNode meta = JSON.readTree(http.getText(URI.create(source.access().url() + "?filters=" + encode(STATE)
                + "&fields=UNINUM&limit=1&format=json"))).required("meta");
        return "fdic:" + meta.required("index").required("name").asString() + ";" + meta.required("total").asString();
    }

    @Override
    public String key(CsvRow row) {
        return row.get("UNINUM");
    }

    @Override
    public RowResult parse(CsvRow row, ParseContext context) {
        String key = key(row);
        if (key == null) {
            return RowResult.rejected("missing UNINUM");
        }
        String category = context.categories().officialCategory(ID, row::get).orElse(null);
        if (category == null) {
            return RowResult.skipped("not a full-service branch");
        }
        String name = row.get("NAME");
        if (name == null) {
            return RowResult.rejected("missing NAME");
        }
        var point = context.readPoint(row.get("LONGITUDE"), row.get("LATITUDE"));
        if (point.failure() != null) {
            return point.failure();
        }
        // The bank's name is what is on the door and in Overture/OSM; the office name ("Harvard Square") is kept.
        var attrs = new LinkedHashMap<String, String>();
        for (String column : List.of("CERT", "OFFNAME", "CITY", "SERVTYPE", "SERVTYPE_DESC", "MAINOFF")) {
            attrs.put(column, row.get(column));
        }
        return RowResult.accepted(SourceRow.of(key, name, category, point.lon(), point.lat(), row.get("ADDRESS"),
                row.get("ZIP"), null, null, attrs));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
