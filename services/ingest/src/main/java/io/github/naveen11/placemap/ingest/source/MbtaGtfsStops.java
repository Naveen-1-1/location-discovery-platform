package io.github.naveen11.placemap.ingest.source;

import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * MBTA stops and stations from the static GTFS feed (about 25 MB zipped; 10,298 entries in {@code stops.txt} on
 * 2026-10-11). The only source that creates transit places (ADR 0032), display-only under ADR 0029. live-hub keys
 * MBTA alerts and predictions by the same {@code stop_id}.
 *
 * <p>Places are stations ({@code location_type} 1) and stops that stand alone on the street (type 0 without a parent
 * station): about 120 stations, 2,440 bus stops and 13 ferry piers in the four towns. Platforms and entrances inside a
 * station are skipped, because the station is the place people look for. The fetch keeps a fixed set of columns from
 * {@code stops.txt}, sorted by {@code stop_id}, and deletes the zip.
 */
@Component
public class MbtaGtfsStops implements SourceAdapter {

    public static final String ID = "mbta_gtfs";

    private static final List<String> COLUMNS = List.of(
            "stop_id", "stop_code", "stop_name", "stop_lat", "stop_lon", "location_type", "parent_station",
            "wheelchair_boarding", "municipality", "on_street", "at_street", "vehicle_type");

    private static final CSVFormat GTFS = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get();

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
        if (!"gtfs".equals(source.access().method())) {
            throw new IllegalStateException(ID + " is registered as " + source.access().method() + ", not gtfs");
        }
        Path zip = target.resolveSibling("gtfs.zip");
        try {
            http.download(URI.create(source.access().url()), zip);
            writeSnapshot(zip, target);
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    /** MBTA publishes an ETag for the zip, so a HEAD request tells whether the schedule changed. */
    @Override
    public String remoteVersion(SourceDefinition source, HttpDownloader http) throws IOException, InterruptedException {
        HttpDownloader.Head head = http.head(URI.create(source.access().url()));
        return "gtfs:" + (head.etag() != null ? head.etag() : head.lastModified());
    }

    void writeSnapshot(Path zip, Path target) throws IOException {
        var rows = new ArrayList<List<String>>();
        try (var file = new ZipFile(zip.toFile())) {
            ZipEntry stops = file.getEntry("stops.txt");
            if (stops == null) {
                throw new IOException("GTFS zip has no stops.txt");
            }
            try (var reader = new BufferedReader(new InputStreamReader(file.getInputStream(stops), StandardCharsets.UTF_8))) {
                reader.mark(1);
                if (reader.read() != '\uFEFF') { // GTFS files often start with a byte-order mark
                    reader.reset();
                }
                CSVParser parser = CSVParser.parse(reader, GTFS);
                List<String> header = parser.getHeaderNames();
                for (String column : COLUMNS) {
                    if (!header.contains(column)) {
                        throw new IOException("stops.txt has no column " + column + "; it has " + header);
                    }
                }
                for (CSVRecord record : parser) {
                    rows.add(COLUMNS.stream().map(record::get).toList());
                }
            }
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
        return row.get("stop_id");
    }

    @Override
    public RowResult parse(CsvRow row, ParseContext context) {
        String key = key(row);
        if (key == null) {
            return RowResult.rejected("missing stop_id");
        }
        String locationType = Optional.ofNullable(row.get("location_type")).orElse("0"); // GTFS: empty means 0
        if ("0".equals(locationType) && row.get("parent_station") != null) {
            return RowResult.skipped("platform inside a station");
        }
        String category = context.categories().officialCategory(ID, column ->
                "location_type".equals(column) ? locationType : row.get(column)).orElse(null);
        if (category == null) {
            return RowResult.skipped("no app category"); // entrances, generic nodes, boarding areas
        }
        String name = row.get("stop_name");
        if (name == null) {
            return RowResult.rejected("missing stop_name");
        }
        double lon;
        double lat;
        try {
            lon = Double.parseDouble(Optional.ofNullable(row.get("stop_lon")).orElse(""));
            lat = Double.parseDouble(Optional.ofNullable(row.get("stop_lat")).orElse(""));
        } catch (NumberFormatException e) {
            return RowResult.rejected("unreadable coordinates");
        }
        Optional<String> problem = context.pointProblem(lon, lat);
        if (problem.isPresent()) {
            return ParseContext.OUTSIDE_AREA.equals(problem.get())
                    ? RowResult.skipped(problem.get())
                    : RowResult.rejected(problem.get());
        }
        var attrs = new LinkedHashMap<String, String>();
        attrs.put("location_type", locationType);
        for (String column : List.of("stop_code", "wheelchair_boarding", "municipality", "on_street", "at_street",
                "vehicle_type")) {
            attrs.put(column, row.get(column));
        }
        return RowResult.accepted(SourceRow.of(key, name, category, lon, lat, null, null, null, null, attrs));
    }
}
