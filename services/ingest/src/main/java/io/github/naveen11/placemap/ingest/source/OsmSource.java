package io.github.naveen11.placemap.ingest.source;

import io.github.naveen11.placemap.ingest.catalog.CategoryRules;
import io.github.naveen11.placemap.ingest.catalog.CoverageArea;
import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenStreetMap from the Geofabrik Massachusetts extract (ADR 0004, 0032), cut down with osmium (plan: "Process OSM
 * with osmium"):
 *
 * <ol>
 *   <li>Download the extract (~300 MB).
 *   <li>{@code osmium extract} to the four towns' envelope; {@code osmium tags-filter} to the tag values that map to
 *       an app category in {@code data/categories.yaml}; {@code osmium export} to GeoJSON lines.
 *   <li>Turn that into the snapshot CSV here: one row per node, way or relation, located at the node or at a point
 *       guaranteed to lie inside the outline, sorted by type and id.
 *   <li>Delete the intermediate files; only the snapshot is kept.
 * </ol>
 *
 * Unchanged POIs give a byte-identical snapshot even though Geofabrik republishes the extract daily, so most runs
 * end as UNCHANGED after the download and the osmium pass.
 */
@Component
public class OsmSource implements SourceAdapter {

    public static final String ID = "osm_geofabrik_massachusetts";

    private static final Logger log = LoggerFactory.getLogger(OsmSource.class);
    private static final GeometryFactory GEOMETRY = new GeometryFactory();
    private static final double ENVELOPE_MARGIN_DEGREES = 0.005; // ~500 m
    private static final long OSMIUM_TIMEOUT_MINUTES = 30;

    /** Tags kept besides the category keys, as snapshot column → OSM tag. */
    private static final Map<String, String> EXTRA_TAGS = linked(
            "name", "name",
            "addr_housenumber", "addr:housenumber",
            "addr_street", "addr:street",
            "addr_postcode", "addr:postcode",
            "addr_city", "addr:city",
            "phone", "phone",
            "contact_phone", "contact:phone",
            "website", "website",
            "contact_website", "contact:website",
            "brand", "brand",
            "brand_wikidata", "brand:wikidata",
            "operator", "operator",
            "opening_hours", "opening_hours");

    private final CoverageArea area;
    private final CategoryRules categories;
    private final String osmium;
    private final List<String> categoryKeys;
    private final List<String> columns;

    public OsmSource(CoverageArea area, CategoryRules categories,
            @Value("${placemap.ingest.osmium:osmium}") String osmium) {
        this.area = area;
        this.categories = categories;
        this.osmium = osmium;
        this.categoryKeys = List.copyOf(categories.osmTags().keySet());
        var columns = new ArrayList<>(List.of("osm_type", "osm_id", "lon", "lat"));
        columns.addAll(categoryKeys);
        columns.addAll(EXTRA_TAGS.keySet());
        this.columns = List.copyOf(columns);
    }

    @Override
    public String sourceId() {
        return ID;
    }

    @Override
    public List<String> columns() {
        return columns;
    }

    @Override
    public void fetch(SourceDefinition source, Path target, HttpDownloader http) throws IOException, InterruptedException {
        if (!"osm_pbf".equals(source.access().method())) {
            throw new IllegalStateException(ID + " is registered as " + source.access().method() + ", not osm_pbf");
        }
        URI uri = URI.create(source.access().url());
        Path dir = target.getParent();
        // osmium picks the input format from the file name, so keep the publisher's (…osm.pbf, or .osm in tests).
        Path download = dir.resolve(Path.of(uri.getPath()).getFileName());
        Path extract = dir.resolve("area.osm.pbf");
        Path pois = dir.resolve("pois.osm.pbf");
        Path features = dir.resolve("pois.geojsonseq");
        try {
            http.download(uri, download);
            double[] b = area.bounds();
            String bbox = "%s,%s,%s,%s".formatted(b[0] - ENVELOPE_MARGIN_DEGREES, b[1] - ENVELOPE_MARGIN_DEGREES,
                    b[2] + ENVELOPE_MARGIN_DEGREES, b[3] + ENVELOPE_MARGIN_DEGREES);
            osmium("extract", "--overwrite", "-b", bbox, download.toString(), "-o", extract.toString());
            var filter = new ArrayList<>(List.of("tags-filter", "--overwrite", extract.toString()));
            categories.osmTags().forEach((key, values) -> filter.add("nwr/" + key + "=" + String.join(",", values)));
            filter.addAll(List.of("-o", pois.toString()));
            osmium(filter.toArray(String[]::new));
            osmium("export", "--overwrite", pois.toString(), "-f", "geojsonseq", "--geometry-types=point,polygon",
                    "-a", "type,id", "-x", "print_record_separator=false", "-o", features.toString());
            writeSnapshot(features, target);
        } finally {
            for (Path p : List.of(download, extract, pois, features)) {
                Files.deleteIfExists(p);
            }
        }
    }

    /**
     * Geofabrik's "-latest" link redirects to a dated file and serves an ETag, so a HEAD request tells whether the
     * ~300 MB extract changed.
     */
    @Override
    public String remoteVersion(SourceDefinition source, HttpDownloader http) throws IOException, InterruptedException {
        HttpDownloader.Head head = http.head(URI.create(source.access().url()));
        return "osm:" + head.uri().getPath() + ";" + (head.etag() != null ? head.etag() : head.lastModified());
    }

    /** GeoJSON lines from osmium → the snapshot CSV, one row per OSM object, sorted by type then id. */
    void writeSnapshot(Path features, Path target) throws IOException {
        var json = new JsonMapper();
        var rows = new ArrayList<List<String>>();
        try (BufferedReader reader = Files.newBufferedReader(features, StandardCharsets.UTF_8)) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (!line.isBlank()) {
                    rows.add(row(json.readTree(line)));
                }
            }
        }
        rows.sort(Comparator.<List<String>>comparingInt(r -> typeOrder(r.get(0))).thenComparingLong(r -> Long.parseLong(r.get(1))));
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try (Writer out = Files.newBufferedWriter(part, StandardCharsets.UTF_8);
                var printer = new CSVPrinter(out, CSVFormat.RFC4180.builder().setHeader(columns.toArray(String[]::new)).get())) {
            for (List<String> row : rows) {
                printer.printRecord(row);
            }
        }
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private List<String> row(JsonNode feature) {
        JsonNode properties = feature.required("properties");
        Coordinate point = representativePoint(feature.required("geometry"));
        var row = new ArrayList<String>(columns.size());
        row.add(properties.required("@type").asString());
        row.add(properties.required("@id").asString());
        row.add(round(point.x));
        row.add(round(point.y));
        for (String key : categoryKeys) {
            row.add(properties.path(key).asString(""));
        }
        for (String tag : EXTRA_TAGS.values()) {
            row.add(properties.path(tag).asString(""));
        }
        return row;
    }

    /** The node itself, or a point guaranteed to lie inside the outline (a centroid can fall outside an L-shape). */
    static Coordinate representativePoint(JsonNode geometry) {
        JsonNode coordinates = geometry.required("coordinates");
        return switch (geometry.required("type").asString()) {
            case "Point" -> position(coordinates);
            case "Polygon" -> polygon(coordinates).getInteriorPoint().getCoordinate();
            case "MultiPolygon" -> {
                List<Polygon> parts = coordinates.values().stream().map(OsmSource::polygon).toList();
                Geometry multi = GEOMETRY.createMultiPolygon(parts.toArray(Polygon[]::new));
                yield multi.getInteriorPoint().getCoordinate();
            }
            default -> throw new IllegalStateException("unexpected geometry " + geometry.get("type"));
        };
    }

    @Override
    public String key(CsvRow row) {
        String type = row.get("osm_type");
        String id = row.get("osm_id");
        return type == null || id == null ? null : type + "/" + id;
    }

    @Override
    public RowResult parse(CsvRow row, ParseContext context) {
        String key = key(row);
        if (key == null) {
            return RowResult.rejected("missing OSM type or id");
        }
        // Many ATMs and some chain stores carry only a brand or an operator ("Bank of America"); without this they
        // would be dropped although they are real places.
        String name = Stream.of(row.get("name"), row.get("brand"), row.get("operator"))
                .filter(s -> s != null).findFirst().orElse(null);
        if (name == null) {
            return RowResult.skipped("no name");
        }
        Optional<String> category = context.categories().osmCategory(row::get);
        if (category.isEmpty()) {
            return RowResult.skipped("no app category");
        }
        if (row.get("lon") == null || row.get("lat") == null) {
            return RowResult.rejected("no coordinates");
        }
        double lon;
        double lat;
        try {
            lon = Double.parseDouble(row.get("lon"));
            lat = Double.parseDouble(row.get("lat"));
        } catch (NumberFormatException e) {
            return RowResult.rejected("unreadable coordinates");
        }
        Optional<String> problem = context.pointProblem(lon, lat);
        if (problem.isPresent()) {
            return ParseContext.OUTSIDE_AREA.equals(problem.get())
                    ? RowResult.skipped(problem.get())
                    : RowResult.rejected(problem.get());
        }
        String street = row.get("addr_street");
        String address = street == null ? null
                : Stream.of(row.get("addr_housenumber"), street).filter(s -> s != null).collect(Collectors.joining(" "));
        var attrs = new LinkedHashMap<String, String>();
        for (String tag : categoryKeys) {
            attrs.put(tag, row.get(tag));
        }
        for (String column : List.of("addr_city", "brand", "brand_wikidata", "operator", "opening_hours")) {
            attrs.put(column, row.get(column));
        }
        String phone = Optional.ofNullable(row.get("phone")).orElse(row.get("contact_phone"));
        String website = Optional.ofNullable(row.get("website")).orElse(row.get("contact_website"));
        return RowResult.accepted(SourceRow.of(key, name, category.get(), lon, lat, address, row.get("addr_postcode"),
                phone, website, attrs));
    }

    private void osmium(String... args) throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add(osmium);
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (var stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!process.waitFor(OSMIUM_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("osmium " + args[0] + " did not finish within " + OSMIUM_TIMEOUT_MINUTES + " minutes");
        }
        if (process.exitValue() != 0) {
            throw new IOException("osmium " + args[0] + " exited with " + process.exitValue() + ": " + output.strip());
        }
        log.debug("osmium {}: {}", args[0], output.strip());
    }

    private static int typeOrder(String type) {
        return switch (type) {
            case "node" -> 0;
            case "way" -> 1;
            case "relation" -> 2;
            default -> 3;
        };
    }

    private static String round(double degrees) {
        // OSM stores 7 decimals; rounding keeps interior points from carrying float noise into the snapshot.
        return BigDecimal.valueOf(degrees).setScale(7, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static Coordinate position(JsonNode p) {
        return new Coordinate(p.get(0).asDouble(), p.get(1).asDouble());
    }

    private static Polygon polygon(JsonNode rings) {
        List<LinearRing> parsed = rings.values().stream()
                .map(ring -> GEOMETRY.createLinearRing(ring.values().stream().map(OsmSource::position).toArray(Coordinate[]::new)))
                .toList();
        return GEOMETRY.createPolygon(parsed.getFirst(), parsed.subList(1, parsed.size()).toArray(LinearRing[]::new));
    }

    private static Map<String, String> linked(String... pairs) {
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
