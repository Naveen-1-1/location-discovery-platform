package io.github.naveen11.placemap.ingest.source;

import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bluebikes docks from the GBFS {@code station_information} feed (626 docks on 2026-10-11, about a third inside the
 * four towns). The only source that creates bike-share places, display-only. Live bike
 * counts come from {@code station_status} in live-hub, keyed by the same {@code station_id}.
 *
 * <p>The feed is one JSON document. The fetch keeps a fixed set of fields per dock and writes them as the snapshot
 * CSV, sorted by {@code station_id}, so an unchanged dock list gives a byte-identical snapshot even though the feed's
 * {@code last_updated} moves every minute.
 */
@Component
public class BluebikesStations implements SourceAdapter {

    public static final String ID = "bluebikes_station_information";

    private static final List<String> COLUMNS = List.of(
            "station_id", "short_name", "name", "lon", "lat", "capacity", "region_id", "station_type", "has_kiosk");

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
        if (!"gbfs".equals(source.access().method())) {
            throw new IllegalStateException(ID + " is registered as " + source.access().method() + ", not gbfs");
        }
        writeSnapshot(http.getText(URI.create(source.access().url())), target);
    }

    void writeSnapshot(String feed, Path target) throws IOException {
        JsonNode stations = JSON.readTree(feed).required("data").required("stations");
        var rows = new ArrayList<List<String>>();
        for (JsonNode station : stations.values()) {
            var row = new ArrayList<String>(COLUMNS.size());
            for (String column : COLUMNS) {
                JsonNode value = station.path(column);
                row.add(value.asString(""));
            }
            rows.add(row);
        }
        SnapshotFiles.writeSorted(target, COLUMNS, rows);
    }

    @Override
    public String key(CsvRow row) {
        return row.get("station_id");
    }

    @Override
    public RowResult parse(CsvRow row, ParseContext context) {
        String key = key(row);
        if (key == null) {
            return RowResult.rejected("missing station_id");
        }
        String name = row.get("name");
        if (name == null) {
            return RowResult.rejected("missing name");
        }
        // Bluebikes also serves Everett, Newton, Salem and more: those docks are skipped, not rejected.
        ParseContext.ReadPoint point = context.readPoint(row.get("lon"), row.get("lat"));
        if (point.failure() != null) {
            return point.failure();
        }
        double lon = point.lon();
        double lat = point.lat();
        String category = context.categories().officialCategory(ID, row::get).orElse(null);
        var attrs = new LinkedHashMap<String, String>();
        for (String column : List.of("short_name", "capacity", "region_id", "station_type", "has_kiosk")) {
            attrs.put(column, row.get(column));
        }
        return RowResult.accepted(SourceRow.of(key, name, category, lon, lat, null, null, null, null, attrs));
    }
}
