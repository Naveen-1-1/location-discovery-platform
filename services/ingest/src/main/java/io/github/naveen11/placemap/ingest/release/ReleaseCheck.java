package io.github.naveen11.placemap.ingest.release;

import io.github.naveen11.placemap.ingest.catalog.CoverageArea;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks a built release folder and lists every problem; none means it may be published. Reads only the
 * files and {@code data/sources.yaml}, never the database, so it can check a folder long after it was built.
 *
 * <ul>
 *   <li>The files are all there, {@code SHA256SUMS} matches them, and {@code LICENSE} is the ODbL text.
 *   <li>Both tables have exactly the expected columns: none named like personal data or comments, and none a source
 *       drops at import.
 *   <li>Every record comes from a source marked {@code "yes"}, every place has one, and no value anywhere names a
 *       display-only source.
 *   <li>Every place is a point inside the four towns (the importer's own area), and ids are unique.
 *   <li>{@code ATTRIBUTION.md} credits every source in the data.
 * </ul>
 */
public final class ReleaseCheck {

    // Column names that would mean personal data or free text written by people, whatever the source.
    private static final Pattern PERSONAL = Pattern.compile(
            "comment|owner|applicant|manager|first_?name|last_?name|surname|email|official|inspector|phone_");
    /** Every text column, scanned for display-only source ids. */
    private static final Map<String, List<String>> TEXT_COLUMNS = Map.of(
            "places", List.of("name", "category", "address", "phone", "website", "hours", "hours_source",
                    "park_features", "inspection", "inspection_source", "inspection_record_id"),
            "place_sources", List.of("source", "record_id", "link"));
    private static final JsonMapper JSON = new JsonMapper();

    private ReleaseCheck() {
    }

    public static List<String> check(Path folder, Map<String, SourceTerms> terms, Path licencesDir, CoverageArea area) {
        var problems = new ArrayList<String>();
        try {
            files(folder, licencesDir, problems);
            if (problems.isEmpty()) {
                data(folder, terms, area, problems);
            }
        } catch (IOException | SQLException e) {
            problems.add("could not read the release: " + e);
        }
        return problems;
    }

    /** The newest {@code YYYY-MM-DD} folder under {@code releaseDir}. */
    public static Optional<Path> newest(Path releaseDir) throws IOException {
        if (!Files.isDirectory(releaseDir)) {
            return Optional.empty();
        }
        try (Stream<Path> folders = Files.list(releaseDir)) {
            return folders.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}"))
                    .max(Path::compareTo);
        }
    }

    private static void files(Path folder, Path licencesDir, List<String> problems) throws IOException {
        for (String name : List.of(ReleaseExport.PLACES, ReleaseExport.PLACE_SOURCES, ReleaseDocs.LICENSE,
                ReleaseDocs.ATTRIBUTION, ReleaseDocs.METHOD, ReleaseDocs.SUMS)) {
            if (!Files.isRegularFile(folder.resolve(name))) {
                problems.add("missing " + name);
            }
        }
        if (!problems.isEmpty()) {
            return;
        }
        if (Files.mismatch(folder.resolve(ReleaseDocs.LICENSE), licencesDir.resolve(ReleaseDocs.ODBL)) != -1) {
            problems.add("LICENSE is not the ODbL 1.0 text in " + licencesDir.resolve(ReleaseDocs.ODBL));
        }
        var listed = new TreeSet<String>();
        for (String line : Files.readAllLines(folder.resolve(ReleaseDocs.SUMS))) {
            String[] parts = line.split(" {2}", 2);
            if (parts.length != 2) {
                problems.add("unreadable line in SHA256SUMS: " + line);
                continue;
            }
            listed.add(parts[1]);
            Path file = folder.resolve(parts[1]).normalize();
            if (!file.startsWith(folder.normalize()) || !Files.isRegularFile(file)) {
                problems.add("SHA256SUMS lists " + parts[1] + ", which is not in the release");
            } else if (!ReleaseDocs.sha256(file).equals(parts[0])) {
                problems.add(parts[1] + " does not match its SHA256SUMS line");
            }
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.filter(Files::isRegularFile)
                    .map(p -> folder.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> !p.equals(ReleaseDocs.SUMS) && !listed.contains(p))
                    .forEach(p -> problems.add(p + " is not in SHA256SUMS"));
        }
    }

    private static void data(Path folder, Map<String, SourceTerms> terms, CoverageArea area, List<String> problems)
            throws SQLException, IOException {
        Set<String> releasable = new TreeSet<>();
        Set<String> displayOnly = new TreeSet<>();
        Set<String> dropped = new TreeSet<>();
        for (SourceTerms source : terms.values()) {
            if (source.releasable()) {
                releasable.add(source.id());
            }
            if (source.displayOnly()) {
                displayOnly.add(source.id());
            }
            source.dropFields().forEach(f -> dropped.add(f.toLowerCase(Locale.ROOT)));
        }
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:"); var s = duck.createStatement()) {
            s.execute("CREATE VIEW places AS SELECT * FROM read_parquet('"
                    + ReleaseExport.sql(folder.resolve(ReleaseExport.PLACES)) + "')");
            s.execute("CREATE VIEW place_sources AS SELECT * FROM read_parquet('"
                    + ReleaseExport.sql(folder.resolve(ReleaseExport.PLACE_SOURCES)) + "')");
            columns(s, "places", ReleaseExport.PLACE_COLUMNS, dropped, problems);
            columns(s, "place_sources", ReleaseExport.PLACE_SOURCE_COLUMNS, dropped, problems);
            if (!problems.isEmpty()) {
                return;
            }
            String open = quoted(releasable);
            count(s, problems, "records from a source not marked \"yes\"",
                    "SELECT count(*) FROM place_sources WHERE source NOT IN (" + open + ")");
            count(s, problems, "inspections from a source not marked \"yes\"",
                    "SELECT count(*) FROM places WHERE inspection_source IS NOT NULL AND inspection_source NOT IN ("
                            + open + ")");
            count(s, problems, "places without a source record",
                    "SELECT count(*) FROM places p WHERE NOT EXISTS (SELECT 1 FROM place_sources r "
                            + "WHERE r.place_id = p.place_id)");
            count(s, problems, "records of places not in places.parquet",
                    "SELECT count(*) FROM place_sources r WHERE NOT EXISTS (SELECT 1 FROM places p "
                            + "WHERE p.place_id = r.place_id)");
            count(s, problems, "repeated place ids",
                    "SELECT count(*) - count(DISTINCT place_id) FROM places");
            count(s, problems, "repeated records",
                    "SELECT count(*) FROM (SELECT source, record_id FROM place_sources GROUP BY 1, 2 HAVING count(*) > 1)");
            count(s, problems, "places without a name or category",
                    "SELECT count(*) FROM places WHERE name IS NULL OR category IS NULL");
            count(s, problems, "places without a point", "SELECT count(*) FROM places WHERE geometry IS NULL");
            for (var table : TEXT_COLUMNS.entrySet()) {
                for (String id : displayOnly) {
                    count(s, problems, "values in " + table.getKey() + " naming the display-only source " + id,
                            "SELECT count(*) FROM " + table.getKey() + " WHERE contains(lower(concat_ws('|', "
                                    + String.join(", ", table.getValue()) + ")), '" + id + "')");
                }
            }
            geoMetadata(s, folder, problems);
            points(s, area.checker(), problems);
            String attribution = Files.readString(folder.resolve(ReleaseDocs.ATTRIBUTION));
            try (var sources = s.executeQuery("SELECT DISTINCT source FROM place_sources ORDER BY 1")) {
                while (sources.next()) {
                    if (!attribution.contains("`" + sources.getString(1) + "`")) {
                        problems.add("ATTRIBUTION.md does not credit " + sources.getString(1));
                    }
                }
            }
        }
    }

    /** The GeoParquet metadata DuckDB wrote: the geometry column, as WKB points. */
    private static void geoMetadata(java.sql.Statement s, Path folder, List<String> problems) throws SQLException {
        String meta = "";
        try (var geo = s.executeQuery("SELECT decode(value) FROM parquet_kv_metadata('"
                + ReleaseExport.sql(folder.resolve(ReleaseExport.PLACES)) + "') WHERE decode(key) = 'geo'")) {
            if (geo.next()) {
                meta = geo.getString(1);
            }
        }
        JsonNode geo = JSON.readTree(meta.isEmpty() ? "{}" : meta);
        JsonNode column = geo.path("columns").path("geometry");
        if (!"geometry".equals(geo.path("primary_column").asString(""))
                || !"WKB".equals(column.path("encoding").asString(""))) {
            problems.add("places.parquet has no GeoParquet metadata for its geometry column");
            return;
        }
        if (!column.path("geometry_types").toString().equals("[\"Point\"]")) {
            problems.add("places.parquet holds geometries other than points: " + column.path("geometry_types"));
        }
    }

    /** A geometry from its well-known text, or null if it is not readable. */
    private static Geometry point(WKTReader wkt, String text) {
        try {
            return wkt.read(text);
        } catch (ParseException e) {
            return null;
        }
    }

    /** Every point inside the four towns, checked with the importer's own polygons. */
    private static void points(java.sql.Statement s, CoverageArea.Checker area, List<String> problems)
            throws SQLException {
        var outside = new ArrayList<Long>();
        var wkt = new WKTReader();
        try (var rows = s.executeQuery("SELECT place_id, CAST(geometry AS VARCHAR) FROM places WHERE geometry IS NOT NULL")) {
            while (rows.next()) {
                if (!(point(wkt, rows.getString(2)) instanceof Point p) || !area.covers(p.getX(), p.getY())) {
                    outside.add(rows.getLong(1));
                }
            }
        }
        if (!outside.isEmpty()) {
            problems.add(outside.size() + " places outside the four towns, such as " + outside.subList(0,
                    Math.min(5, outside.size())));
        }
    }

    private static void columns(java.sql.Statement s, String table, List<String> expected, Set<String> dropped,
            List<String> problems) throws SQLException {
        var actual = new ArrayList<String>();
        try (var rows = s.executeQuery("SELECT column_name FROM (DESCRIBE " + table + ")")) {
            while (rows.next()) {
                actual.add(rows.getString(1));
            }
        }
        if (!actual.equals(expected)) {
            problems.add(table + " has columns " + actual + ", expected " + expected);
        }
        for (String column : actual) {
            String lower = column.toLowerCase(Locale.ROOT);
            if (PERSONAL.matcher(lower).find() || dropped.contains(lower)) {
                problems.add(table + "." + column + " looks like personal data or free text");
            }
        }
    }

    private static void count(java.sql.Statement s, List<String> problems, String what, String sql)
            throws SQLException {
        try (var rows = s.executeQuery(sql)) {
            rows.next();
            long n = rows.getLong(1);
            if (n != 0) {
                problems.add(n + " " + what);
            }
        }
    }

    private static String quoted(Set<String> ids) {
        return String.join(", ", ids.stream().map(id -> "'" + id.replace("'", "''") + "'").toList());
    }
}
