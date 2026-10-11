package io.github.naveen11.placemap.ingest.source;

import io.github.naveen11.placemap.ingest.catalog.CoverageArea;
import io.github.naveen11.placemap.ingest.catalog.SourceDefinition;
import io.github.naveen11.placemap.ingest.snapshot.HttpDownloader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Overture Maps Places (ADR 0004, 0032), read straight from the release's GeoParquet on S3 with DuckDB. Only rows
 * whose bounding box falls in the four towns' envelope are fetched (DuckDB pushes that filter into the Parquet
 * scan), and only the columns below. Rows come out sorted by Overture id, so the same release always gives a
 * byte-identical snapshot.
 *
 * <p>Out-of-scope rows are skipped, not rejected: places in the envelope but outside the towns, places with no app
 * category, and places Overture marks permanently closed (ADR 0032). The confidence rule (stand alone at 0.9 or
 * above, otherwise needs corroboration) belongs to matching, so confidence is kept in attrs.
 */
@Component
public class OvertureSource implements SourceAdapter {

    public static final String ID = "overture_places";

    private static final List<String> COLUMNS = List.of(
            "id", "name", "basic_category", "taxonomy_primary", "confidence", "operating_status", "lon", "lat",
            "address", "locality", "postcode", "phone", "website", "brand_wikidata", "datasets");

    // Points have xmin = xmax and ymin = ymax, so the bbox gives the coordinates without DuckDB's spatial extension.
    // datasets lists the upstream providers, which decides the attribution the ODbL release needs (sources.yaml).
    private static final String EXTRACT = """
            COPY (
              SELECT id, names."primary" AS name, basic_category, taxonomy."primary" AS taxonomy_primary, confidence,
                     operating_status, bbox.xmin AS lon, bbox.ymin AS lat,
                     addresses[1].freeform AS address, addresses[1].locality AS locality,
                     addresses[1].postcode AS postcode, phones[1] AS phone, websites[1] AS website,
                     brand.wikidata AS brand_wikidata,
                     array_to_string(list_sort(list_distinct([s.dataset FOR s IN sources])), ';') AS datasets
              FROM read_parquet('%s', hive_partitioning = true)
              WHERE bbox.xmin BETWEEN %s AND %s AND bbox.ymin BETWEEN %s AND %s
              ORDER BY id
            ) TO '%s' (FORMAT csv, HEADER true)
            """;
    private static final double ENVELOPE_MARGIN_DEGREES = 0.005; // ~500 m

    private final CoverageArea area;

    public OvertureSource(CoverageArea area) {
        this.area = area;
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
        if (!"geoparquet_s3".equals(source.access().method())) {
            throw new IllegalStateException(ID + " is registered as " + source.access().method() + ", not geoparquet_s3");
        }
        String location = source.access().url();
        String files = location.endsWith("/") ? location + "*" : location;
        double[] b = area.bounds();
        Path part = target.resolveSibling(target.getFileName() + ".part");
        String sql = EXTRACT.formatted(quote(files),
                b[0] - ENVELOPE_MARGIN_DEGREES, b[2] + ENVELOPE_MARGIN_DEGREES,
                b[1] - ENVELOPE_MARGIN_DEGREES, b[3] + ENVELOPE_MARGIN_DEGREES,
                quote(part.toString()));
        PlatformThreads.run("ingest-duckdb-" + ID, () -> {
            try (var connection = DriverManager.getConnection("jdbc:duckdb:");
                    var statement = connection.createStatement()) {
                if (files.startsWith("s3://")) {
                    statement.execute("INSTALL httpfs");
                    statement.execute("LOAD httpfs");
                    statement.execute("SET s3_region = 'us-west-2'"); // the public overturemaps-us-west-2 bucket
                }
                statement.execute(sql);
            }
        });
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
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
        String name = row.get("name");
        if (name == null) {
            return RowResult.skipped("no name");
        }
        if ("permanently_closed".equals(row.get("operating_status"))) {
            return RowResult.skipped("permanently closed");
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
            // The envelope query fetches places just across the town lines too; those are expected, not defects.
            return ParseContext.OUTSIDE_AREA.equals(problem.get())
                    ? RowResult.skipped(problem.get())
                    : RowResult.rejected(problem.get());
        }
        Optional<String> category = context.categories().overtureCategory(row::get);
        if (category.isEmpty()) {
            return RowResult.skipped("no app category");
        }
        var attrs = new LinkedHashMap<String, String>();
        for (String column : List.of("basic_category", "taxonomy_primary", "confidence", "operating_status", "locality",
                "brand_wikidata", "datasets")) {
            attrs.put(column, row.get(column));
        }
        return RowResult.accepted(SourceRow.of(key, name, category.get(), lon, lat, row.get("address"),
                row.get("postcode"), row.get("phone"), row.get("website"), attrs));
    }

    private static String quote(String text) {
        return text.replace("'", "''");
    }
}
