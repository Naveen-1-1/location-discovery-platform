package io.github.naveen11.placemap.ingest.source;

import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Boston's open-space parcels (557 on 2026-10-11), an existence source for parks next to OSM ("polygons
 * decide"). The CSV resource has no geometry, so the fetch reads the GeoJSON resource and keeps, per parcel, a point
 * that lies inside it and its outline simplified to about 2 m. Matching compares other records with the outline, so
 * an OSM park whose point is far from ours still matches.
 *
 * <p>Only parks, playgrounds, reservations, urban wilds and community gardens get the park category
 * ({@code TypeLong} in {@code data/categories.yaml}); cemeteries, traffic squares and plazas are skipped.
 */
@Component
public class BostonOpenSpace implements SourceAdapter {

    public static final String ID = "boston_open_space";

    private static final List<String> PROPERTIES = List.of(
            "OS_ID", "SITE_NAME", "ALT_NAME", "TypeLong", "ADDRESS", "ZipCode", "DISTRICT", "ACRES", "OWNERSHIP",
            "OS_Own_Jur", "OS_Mngmnt");
    private static final List<String> COLUMNS =
            Stream.concat(PROPERTIES.stream(), Stream.of("lon", "lat", "outline")).toList();

    private static final Pattern RESOURCE_ID = Pattern.compile("/resource/([0-9a-f-]{36})/");
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
        if (!"geojson".equals(source.access().method())) {
            throw new IllegalStateException(ID + " is registered as " + source.access().method() + ", not geojson");
        }
        Path geojson = target.resolveSibling("open_space.geojson");
        try {
            http.download(URI.create(source.access().url()), geojson);
            writeSnapshot(geojson, target);
        } finally {
            Files.deleteIfExists(geojson);
        }
    }

    void writeSnapshot(Path geojson, Path target) throws IOException {
        var rows = new ArrayList<List<String>>();
        for (JsonNode feature : JSON.readTree(geojson.toFile()).required("features").values()) {
            JsonNode properties = feature.required("properties");
            var row = new ArrayList<String>(COLUMNS.size());
            for (String property : PROPERTIES) {
                JsonNode value = properties.path(property);
                String text = value.asString("");
                row.add("NULL".equals(text) ? "" : text); // the publisher writes some blanks as the word NULL
            }
            JsonNode geometry = feature.path("geometry");
            if (geometry.isObject()) {
                Geometry shape = GeoJson.read(geometry);
                Coordinate inside = GeoJson.representativePoint(shape);
                String outline = GeoJson.outlineWkt(shape);
                row.add(Double.toString(inside.x));
                row.add(Double.toString(inside.y));
                row.add(outline == null ? "" : outline);
            } else {
                row.addAll(List.of("", "", ""));
            }
            rows.add(row);
        }
        SnapshotFiles.writeSorted(target, COLUMNS, rows);
    }

    /** The GeoJSON resource's last-modified time from CKAN; the download is skipped while it is unchanged. */
    @Override
    public String remoteVersion(SourceDefinition source, HttpDownloader http) throws IOException, InterruptedException {
        URI url = URI.create(source.access().url());
        Matcher resource = RESOURCE_ID.matcher(url.getPath());
        if (!resource.find()) {
            throw new IllegalStateException(ID + ": no CKAN resource id in " + url.getPath());
        }
        return CkanCsvSource.resourceVersion(http, url, resource.group(1));
    }

    @Override
    public String key(CsvRow row) {
        return row.get("OS_ID");
    }

    @Override
    public RowResult parse(CsvRow row, ParseContext context) {
        String key = key(row);
        if (key == null) {
            return RowResult.rejected("missing OS_ID");
        }
        String category = context.categories().officialCategory(ID, row::get).orElse(null);
        if (category == null) {
            return RowResult.skipped("not a park: " + row.get("TypeLong"));
        }
        String name = row.get("SITE_NAME");
        if (name == null) {
            return RowResult.rejected("missing SITE_NAME");
        }
        var point = context.readPoint(row.get("lon"), row.get("lat"));
        if (point.failure() != null) {
            return point.failure();
        }
        var attrs = new LinkedHashMap<String, String>();
        for (String property : List.of("ALT_NAME", "TypeLong", "DISTRICT", "ACRES", "OWNERSHIP", "OS_Own_Jur",
                "OS_Mngmnt")) {
            attrs.put(property, row.get(property));
        }
        SourceRow record = SourceRow.of(key, name, category, point.lon(), point.lat(), row.get("ADDRESS"),
                row.get("ZipCode"), null, null, attrs);
        String outline = row.get("outline");
        return RowResult.accepted(outline == null ? record : record.withOutline(outline));
    }
}
