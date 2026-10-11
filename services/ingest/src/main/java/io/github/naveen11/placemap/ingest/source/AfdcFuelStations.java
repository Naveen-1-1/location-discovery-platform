package io.github.naveen11.placemap.ingest.source;

import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Public EV charging stations from the DOE's Alternative Fuels Data Center (4,615 open public stations in
 * Massachusetts on 2026-10-11, one request). The only source that creates EV-charging places (ADR 0032: 1,320 in
 * the four towns against OSM's 49).
 *
 * <p>The API needs a free key ({@code NLR_API_KEY} in {@code .env}). It is sent in the {@code X-Api-Key} header, never
 * in the URL, so it cannot end up in a log line or an error message.
 */
@Component
public class AfdcFuelStations implements SourceAdapter {

    public static final String ID = "afdc_alt_fuel_stations";

    private static final List<String> COLUMNS = List.of(
            "id", "station_name", "street_address", "city", "state", "zip", "longitude", "latitude", "fuel_type_code",
            "access_code", "status_code", "ev_level1_evse_num", "ev_level2_evse_num", "ev_dc_fast_num", "ev_network",
            "ev_connector_types", "access_days_time", "facility_type", "owner_type_code", "date_last_confirmed",
            "station_phone");
    private static final String FILTER = "?state=MA&fuel_type=ELEC&access=public&status=E&limit=all";

    private static final JsonMapper JSON = new JsonMapper();

    private final UnaryOperator<String> environment;

    @Autowired
    public AfdcFuelStations(Environment environment) {
        this(name -> environment.getProperty(name, ""));
    }

    AfdcFuelStations(UnaryOperator<String> environment) {
        this.environment = environment;
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
    public void fetch(SourceDefinition source, Path target, HttpDownloader http) throws IOException, InterruptedException {
        if (!"rest_api".equals(source.access().method())) {
            throw new IllegalStateException(ID + " is registered as " + source.access().method() + ", not rest_api");
        }
        writeSnapshot(http.getText(URI.create(source.access().url() + FILTER), keyHeader(source)), target);
    }

    void writeSnapshot(String answer, Path target) throws IOException {
        JsonNode root = JSON.readTree(answer);
        List<JsonNode> stations = root.required("fuel_stations").values().stream().toList();
        int total = root.required("total_results").asInt();
        if (total != stations.size()) {
            throw new IOException("AFDC returned " + stations.size() + " of " + total + " stations");
        }
        var rows = new ArrayList<List<String>>();
        for (JsonNode station : stations) {
            var row = new ArrayList<String>(COLUMNS.size());
            for (String column : COLUMNS) {
                JsonNode value = station.path(column);
                if (value.isArray()) { // ev_connector_types: ["J1772", "J1772COMBO"]
                    row.add(String.join(" ", value.values().stream().map(JsonNode::asString).toList()));
                } else {
                    row.add(value.isMissingNode() || value.isNull() ? "" : value.asString());
                }
            }
            rows.add(row);
        }
        SnapshotFiles.writeSorted(target, COLUMNS, rows);
    }

    /** The time AFDC's station data last changed, from its {@code last-updated} endpoint. */
    @Override
    public String remoteVersion(SourceDefinition source, HttpDownloader http) throws IOException, InterruptedException {
        URI lastUpdated = URI.create(source.access().url().replaceFirst("\\.json$", "/last-updated.json"));
        return "afdc:" + JSON.readTree(http.getText(lastUpdated, keyHeader(source))).required("last_updated").asString();
    }

    @Override
    public Optional<String> notReady(SourceDefinition source) {
        return apiKey(source).isBlank()
                ? Optional.of(source.access().apiKeyEnv() + " is empty in .env; AFDC needs a free key from "
                        + "https://developer.nlr.gov/signup/")
                : Optional.empty();
    }

    private String apiKey(SourceDefinition source) {
        String variable = source.access().apiKeyEnv();
        String key = variable == null ? null : environment.apply(variable);
        return key == null ? "" : key.strip();
    }

    private Map<String, String> keyHeader(SourceDefinition source) throws IOException {
        Optional<String> problem = notReady(source);
        if (problem.isPresent()) {
            throw new IOException(problem.get());
        }
        return Map.of("X-Api-Key", apiKey(source));
    }

    @Override
    public String key(CsvRow row) {
        return row.get("id");
    }

    @Override
    public RowResult parse(CsvRow row, ParseContext context) {
        String key = key(row);
        if (key == null) {
            return RowResult.rejected("missing id");
        }
        String name = row.get("station_name");
        if (name == null) {
            return RowResult.rejected("missing station_name");
        }
        var point = context.readPoint(row.get("longitude"), row.get("latitude"));
        if (point.failure() != null) {
            return point.failure();
        }
        String category = context.categories().officialCategory(ID, row::get).orElse(null);
        var attrs = new LinkedHashMap<String, String>();
        for (String column : List.of("city", "fuel_type_code", "access_code", "status_code", "ev_level1_evse_num",
                "ev_level2_evse_num", "ev_dc_fast_num", "ev_network", "ev_connector_types", "access_days_time",
                "facility_type", "owner_type_code", "date_last_confirmed")) {
            attrs.put(column, row.get(column));
        }
        return RowResult.accepted(SourceRow.of(key, name, category, point.lon(), point.lat(), row.get("street_address"),
                row.get("zip"), row.get("station_phone"), null, attrs));
    }
}
