package io.github.naveen11.placemap.ingest.source;

import io.github.naveen11.placemap.ingest.catalog.CoverageArea;
import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A point layer on an ArcGIS FeatureServer (USDA SNAP retailers, MassGIS hospitals). The fetch asks the server for
 * only {@link #fields()}, only features in the four towns' bounding box, and coordinates in WGS84 ({@code outSR=4326}),
 * so no reprojection is needed even for MassGIS's EPSG:26986 layers. Pages of the layer's {@code maxRecordCount}
 * features, ordered by the key field, are joined into one snapshot whose columns are {@link #fields()} plus
 * {@code lon} and {@code lat}.
 *
 * <p>ArcGIS reports errors as HTTP 200 with an {@code error} object, which fails the fetch like any HTTP error.
 */
public abstract class ArcgisFeatureSource implements SourceAdapter {

    static final String LON = "lon";
    static final String LAT = "lat";

    private static final JsonMapper JSON = new JsonMapper();
    private static final int DEFAULT_PAGE = 1000;
    private static final double MARGIN_DEGREES = 0.005; // ~500 m, like the OSM and Overture extracts

    private final CoverageArea area;

    protected ArcgisFeatureSource(CoverageArea area) {
        this.area = area;
    }

    /** The layer's attribute fields, in snapshot order. The first identifies a feature and orders the pages. */
    protected abstract List<String> fields();

    /** A SQL filter on the layer's attributes; every feature by default. */
    protected String where() {
        return "1=1";
    }

    @Override
    public final List<String> columns() {
        return Stream.concat(fields().stream(), Stream.of(LON, LAT)).toList();
    }

    @Override
    public String key(CsvRow row) {
        return row.get(fields().getFirst());
    }

    @Override
    public void fetch(SourceDefinition source, Path target, HttpDownloader http) throws IOException, InterruptedException {
        if (!"arcgis_rest".equals(source.access().method())) {
            throw new IllegalStateException(sourceId() + " is registered as " + source.access().method() + ", not arcgis_rest");
        }
        String layer = source.access().url();
        JsonNode metadata = checked(JSON.readTree(http.getText(URI.create(layer + "?f=json"))));
        Set<String> published = new HashSet<>();
        metadata.path("fields").values().forEach(f -> published.add(f.path("name").asString()));
        List<String> missing = fields().stream().filter(f -> !published.contains(f)).toList();
        if (!missing.isEmpty()) {
            throw new IOException(sourceId() + ": the layer no longer has fields " + missing + "; it has " + published);
        }
        int page = metadata.path("maxRecordCount").asInt(DEFAULT_PAGE);

        double[] b = area.bounds();
        String envelope = String.format(Locale.ROOT,
                "{\"xmin\":%.6f,\"ymin\":%.6f,\"xmax\":%.6f,\"ymax\":%.6f,\"spatialReference\":{\"wkid\":4326}}",
                b[0] - MARGIN_DEGREES, b[1] - MARGIN_DEGREES, b[2] + MARGIN_DEGREES, b[3] + MARGIN_DEGREES);
        var rows = new ArrayList<List<String>>();
        for (int offset = 0; ; offset += page) {
            String query = layer + "/query?where=" + encode(where())
                    + "&geometry=" + encode(envelope)
                    + "&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects"
                    + "&outFields=" + encode(String.join(",", fields()))
                    + "&orderByFields=" + encode(fields().getFirst() + " ASC")
                    + "&returnGeometry=true&outSR=4326"
                    + "&resultOffset=" + offset + "&resultRecordCount=" + page + "&f=json";
            JsonNode answer = checked(JSON.readTree(http.getText(URI.create(query))));
            List<JsonNode> features = answer.required("features").values().stream().toList();
            for (JsonNode feature : features) {
                rows.add(row(feature));
            }
            if (features.size() < page && !answer.path("exceededTransferLimit").asBoolean(false)) {
                break;
            }
        }
        SnapshotFiles.writeSorted(target, columns(), rows);
    }

    /** When the layer's data last changed, if the server tracks edits (USDA's does; MassGIS's does not). */
    @Override
    public String remoteVersion(SourceDefinition source, HttpDownloader http) throws IOException, InterruptedException {
        JsonNode metadata = checked(JSON.readTree(http.getText(URI.create(source.access().url() + "?f=json"))));
        JsonNode edited = metadata.path("editingInfo").path("dataLastEditDate");
        return edited.isMissingNode() || edited.isNull() ? null : "arcgis:" + edited.asString();
    }

    private List<String> row(JsonNode feature) {
        JsonNode attributes = feature.required("attributes");
        var row = new ArrayList<String>(fields().size() + 2);
        for (String field : fields()) {
            JsonNode value = attributes.path(field);
            row.add(value.isMissingNode() || value.isNull() ? "" : value.asString());
        }
        JsonNode geometry = feature.path("geometry");
        row.add(geometry.path("x").isNumber() ? geometry.get("x").asString() : "");
        row.add(geometry.path("y").isNumber() ? geometry.get("y").asString() : "");
        return row;
    }

    private JsonNode checked(JsonNode answer) throws IOException {
        if (answer.has("error")) {
            JsonNode error = answer.get("error");
            throw new IOException(sourceId() + ": ArcGIS error " + error.path("code").asString() + " "
                    + error.path("message").asString());
        }
        return answer;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
