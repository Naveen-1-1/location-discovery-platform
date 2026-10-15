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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Overture Maps Places, read straight from the release's GeoParquet on S3 with DuckDB. Only rows
 * whose bounding box falls in the four towns' envelope are fetched (DuckDB pushes that filter into the Parquet
 * scan), and only the columns below. Rows come out sorted by Overture id, so the same release always gives a
 * byte-identical snapshot.
 *
 * <p>Out-of-scope rows are skipped, not rejected: places in the envelope but outside the towns, places with no app
 * category, and places Overture marks permanently closed. The confidence rule (stand alone at 0.9 or
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
    private final AsyncTaskExecutor nativeCalls;

    /** @param nativeCalls platform threads: DuckDB is native code, which would pin a virtual thread's carrier */
    public OvertureSource(CoverageArea area, @Qualifier("nativeCalls") AsyncTaskExecutor nativeCalls) {
        this.area = area;
        this.nativeCalls = nativeCalls;
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
        Future<?> extract = nativeCalls.submit(() -> {
            try (var connection = DriverManager.getConnection("jdbc:duckdb:");
                    var statement = connection.createStatement()) {
                if (files.startsWith("s3://")) {
                    statement.execute("INSTALL httpfs");
                    statement.execute("LOAD httpfs");
                    statement.execute("SET s3_region = 'us-west-2'"); // the public overturemaps-us-west-2 bucket
                }
                statement.execute(sql);
            }
            return null;
        });
        try {
            extract.get();
        } catch (InterruptedException e) {
            extract.cancel(true); // best effort: native code may not notice
            throw e;
        } catch (ExecutionException e) {
            throw new IOException("DuckDB could not extract " + ID + ": " + e.getCause().getMessage(), e.getCause());
        }
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** The registry pins one release in the URL, and releases never change, so the URL is the version. */
    @Override
    public String remoteVersion(SourceDefinition source, HttpDownloader http) {
        return "overture:" + source.access().url();
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
        // The envelope query fetches places just across the town lines too: those are skipped, not rejected.
        ParseContext.ReadPoint point = context.readPoint(row.get("lon"), row.get("lat"));
        if (point.failure() != null) {
            return point.failure();
        }
        double lon = point.lon();
        double lat = point.lat();
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
