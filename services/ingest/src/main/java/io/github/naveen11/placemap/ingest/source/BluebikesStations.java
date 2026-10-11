package io.github.naveen11.placemap.ingest.source;

import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bluebikes docks from the GBFS {@code station_information} feed (626 docks on 2026-10-11, about a third inside the
 * four towns). The only source that creates bike-share places (ADR 0032), display-only under ADR 0029. Live bike
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
                row.add(value.isMissingNode() || value.isNull() ? "" : value.asString());
            }
            rows.add(row);
        }
        rows.sort(Comparator.comparing(r -> r.getFirst()));
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try (Writer out = Files.newBufferedWriter(part, StandardCharsets.UTF_8);
                var printer = new CSVPrinter(out, CSVFormat.RFC4180.builder().setHeader(COLUMNS.toArray(String[]::new)).get())) {
            for (List<String> row : rows) {
                printer.printRecord(row);
            }
        }
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
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
        double lon;
        double lat;
        try {
            lon = Double.parseDouble(Optional.ofNullable(row.get("lon")).orElse(""));
            lat = Double.parseDouble(Optional.ofNullable(row.get("lat")).orElse(""));
        } catch (NumberFormatException e) {
            return RowResult.rejected("unreadable coordinates");
        }
        Optional<String> problem = context.pointProblem(lon, lat);
        if (problem.isPresent()) {
            // Bluebikes also serves Everett, Newton, Salem and more; those docks are valid, just not ours.
            return ParseContext.OUTSIDE_AREA.equals(problem.get())
                    ? RowResult.skipped(problem.get())
                    : RowResult.rejected(problem.get());
        }
        String category = context.categories().officialCategory(ID, row::get).orElse(null);
        var attrs = new LinkedHashMap<String, String>();
        for (String column : List.of("short_name", "capacity", "region_id", "station_type", "has_kiosk")) {
            attrs.put(column, row.get(column));
        }
        return RowResult.accepted(SourceRow.of(key, name, category, lon, lat, null, null, null, null, attrs));
    }
}
