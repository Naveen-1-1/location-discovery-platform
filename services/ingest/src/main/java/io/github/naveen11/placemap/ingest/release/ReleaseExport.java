package io.github.naveen11.placemap.ingest.release;

import io.github.naveen11.placemap.ingest.catalog.CoverageArea;
import io.github.naveen11.placemap.ingest.catalog.SourcePriority;
import io.github.naveen11.placemap.ingest.source.OvertureSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.util.FileSystemUtils;

/**
 * Builds the ODbL data release into {@code <releaseDir>/<date>/}: the places and their source records as
 * GeoParquet, the licence, the attribution, the method, the licence texts the sources need and checksums.
 *
 * <p>Only records of sources marked {@code redistribution: "yes"} are read. Over them the app's own merge runs again
 * (the completion step in {@code RunStore}): the same source ranks and {@code names_from} choose each place's name,
 * category, point, address, phone and website, and a place is released only when one of its open records comes from
 * an existence source for its category. So a place the app shows only because of a display-only source (an MBTA stop,
 * a Bluebikes dock) is left out, and no display-only value reaches a released place.
 *
 * <p>Everything is read in one repeatable-read transaction, so the files describe one moment of the database; the
 * transaction is rolled back, as it only creates temporary tables. The folder is built under a temporary name, checked
 * by {@link ReleaseCheck}, and only then given its final name.
 */
public final class ReleaseExport {

    public static final String PLACES = "places.parquet";
    public static final String PLACE_SOURCES = "place_sources.parquet";
    public static final List<String> PLACE_COLUMNS = List.of("place_id", "name", "category", "address", "phone",
            "website", "accepts_snap", "active_licence", "hours", "hours_source", "park_features", "inspection",
            "inspected_at", "inspection_source", "inspection_record_id", "geometry");
    public static final List<String> PLACE_SOURCE_COLUMNS = List.of("place_id", "source", "record_id", "link", "score");

    /** What the release holds, for the documents and the summary. */
    public record Summary(Path folder, int places, int records, Map<String, Integer> byCategory,
            Map<String, Integer> bySource, TreeSet<String> overtureProviders) {
    }

    private static final String RECORDS = """
            CREATE TEMP TABLE release_record AS
            SELECT l.place_id, l.method, l.score, r.source, r.source_key, r.name, r.category, r.geom, r.address,
                   r.phone, r.website,
                   CASE WHEN r.source = ? THEN r.attrs->>'datasets' END AS datasets,
                   coalesce(sr.rank, 1000) AS rank, coalesce(sr.name_rank, 1000) AS name_rank,
                   coalesce(sr.existence, false) AS existence
            FROM place.place p
            JOIN place.place_source_link l ON l.place_id = p.place_id
            JOIN place.source_record r ON r.source = l.source AND r.source_key = l.source_key AND r.retired_at IS NULL
            LEFT JOIN source_role sr ON sr.category = r.category AND sr.source = r.source
            WHERE p.retired_at IS NULL AND r.source = ANY (?)
            """;
    // The completion step's merge (RunStore), over the open records only.
    private static final String PLACES_MERGED = """
            CREATE TEMP TABLE release_place AS
            SELECT place_id
            FROM (SELECT place_id,
                         (array_agg(category ORDER BY rank, source, source_key)
                             FILTER (WHERE category IS NOT NULL))[1] AS category,
                         bool_or(existence) AS established,
                         bool_or(geom IS NOT NULL) AS located
                  FROM release_record GROUP BY place_id) m
            WHERE established AND located AND category IS NOT NULL
            """;
    private static final String PLACE_ROWS = """
            WITH best AS (
                SELECT place_id,
                       (array_agg(name ORDER BY name_rank, rank, source, source_key))[1] AS name,
                       (array_agg(category ORDER BY rank, source, source_key)
                           FILTER (WHERE category IS NOT NULL))[1] AS category,
                       (array_agg(geom ORDER BY rank, source, source_key) FILTER (WHERE geom IS NOT NULL))[1] AS geom,
                       (array_agg(address ORDER BY rank, source, source_key)
                           FILTER (WHERE address IS NOT NULL))[1] AS address,
                       (array_agg(phone ORDER BY rank, source, source_key) FILTER (WHERE phone IS NOT NULL))[1] AS phone,
                       (array_agg(website ORDER BY rank, source, source_key)
                           FILTER (WHERE website IS NOT NULL))[1] AS website
                FROM release_record JOIN release_place USING (place_id)
                GROUP BY place_id)
            SELECT b.place_id, b.name, b.category, b.address, b.phone, b.website,
                   f.accepts_snap, f.active_licence, f.hours, f.hours_source, f.park_features, f.inspection,
                   f.inspected_at, latest.source AS inspection_source, latest.source_key AS inspection_record_id,
                   ST_AsText(b.geom::geometry) AS wkt
            FROM best b
            LEFT JOIN place.place_facts f ON f.place_id = b.place_id
            -- The record of the latest inspection, chosen as the facts choose it (PlaceFacts).
            LEFT JOIN LATERAL (
                SELECT s.source, s.source_key
                FROM release_record rr
                JOIN place.inspection_summary s ON s.source = rr.source AND s.source_key = rr.source_key
                WHERE rr.place_id = b.place_id
                ORDER BY s.latest_at DESC, s.source, s.source_key
                LIMIT 1) latest ON f.inspection IS NOT NULL
            ORDER BY b.place_id
            """;
    private static final String SOURCE_ROWS = """
            SELECT rr.place_id, rr.source, rr.source_key, rr.method, rr.score, rr.datasets
            FROM release_record rr JOIN release_place USING (place_id)
            ORDER BY rr.place_id, rr.source, rr.source_key
            """;

    private ReleaseExport() {
    }

    public static Summary build(DataSource dataSource, Map<String, SourceTerms> terms, SourcePriority priority,
            CoverageArea area, Path licencesDir, Path releaseDir, LocalDate date) throws IOException, SQLException {
        Path folder = releaseDir.resolve(date.toString());
        Path partial = releaseDir.resolve("." + date + ".partial");
        deleteTree(partial);
        Files.createDirectories(partial);
        try {
            Summary summary = export(dataSource, terms, priority, partial);
            ReleaseDocs.write(partial, terms, licencesDir, date, summary);
            List<String> problems = ReleaseCheck.check(partial, terms, licencesDir, area);
            if (!problems.isEmpty()) {
                throw new IllegalStateException("the release failed its checks:\n  - " + String.join("\n  - ", problems));
            }
            deleteTree(folder);
            Files.move(partial, folder, StandardCopyOption.ATOMIC_MOVE);
            return new Summary(folder, summary.places(), summary.records(), summary.byCategory(), summary.bySource(),
                    summary.overtureProviders());
        } finally {
            deleteTree(partial);
        }
    }

    private static Summary export(DataSource dataSource, Map<String, SourceTerms> terms, SourcePriority priority,
            Path folder) throws SQLException {
        String[] open = terms.values().stream().filter(SourceTerms::releasable).map(SourceTerms::id)
                .toArray(String[]::new);
        try (Connection pg = dataSource.getConnection();
                var duck = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
            pg.setAutoCommit(false);
            pg.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                var tx = JdbcClient.create(new SingleConnectionDataSource(pg, true));
                tx.sql("""
                        CREATE TEMP TABLE source_role (category text, source text, rank int, existence boolean,
                                                       name_rank int, PRIMARY KEY (category, source))
                        """).update();
                for (SourcePriority.Role role : priority.roles()) {
                    tx.sql("INSERT INTO source_role VALUES (?, ?, ?, ?, ?)")
                            .params(role.category(), role.source(), role.rank(), role.existence(), role.nameRank())
                            .update();
                }
                try (var records = pg.prepareStatement(RECORDS)) {
                    records.setString(1, OvertureSource.ID);
                    records.setArray(2, pg.createArrayOf("text", open));
                    records.execute();
                }
                tx.sql(PLACES_MERGED).update();
                createTables(duck);
                var byCategory = new TreeMap<String, Integer>();
                int places;
                try (var appender = duck.createAppender("places"); var rows = pg.prepareStatement(PLACE_ROWS)) {
                    rows.setFetchSize(1000);
                    places = copyPlaces(rows.executeQuery(), appender, byCategory);
                }
                var bySource = new TreeMap<String, Integer>();
                var providers = new TreeSet<String>();
                int records;
                try (var appender = duck.createAppender("place_sources"); var rows = pg.prepareStatement(SOURCE_ROWS)) {
                    rows.setFetchSize(1000);
                    records = copySources(rows.executeQuery(), appender, bySource, providers);
                }
                write(duck, folder);
                return new Summary(folder, places, records, byCategory, bySource, providers);
            } finally {
                pg.rollback(); // only temporary tables were written
                pg.setAutoCommit(true);
            }
        }
    }

    /** The two tables in DuckDB, in the release's column order; the point arrives as WKT. */
    static void createTables(Connection duck) throws SQLException {
        try (var s = duck.createStatement()) {
            s.execute("""
                    CREATE TABLE places (place_id BIGINT, name VARCHAR, category VARCHAR, address VARCHAR,
                        phone VARCHAR, website VARCHAR, accepts_snap BOOLEAN, active_licence BOOLEAN,
                        hours VARCHAR, hours_source VARCHAR, park_features VARCHAR, inspection VARCHAR,
                        inspected_at TIMESTAMPTZ, inspection_source VARCHAR, inspection_record_id VARCHAR,
                        wkt VARCHAR)""");
            s.execute("""
                    CREATE TABLE place_sources (place_id BIGINT, source VARCHAR, record_id VARCHAR,
                        link VARCHAR, score REAL)""");
        }
    }

    private static int copyPlaces(ResultSet rows, DuckDBAppender appender, Map<String, Integer> byCategory)
            throws SQLException {
        int count = 0;
        while (rows.next()) {
            appender.beginRow();
            appender.append(rows.getLong("place_id"));
            for (String column : List.of("name", "category", "address", "phone", "website")) {
                text(appender, rows.getString(column));
            }
            bool(appender, rows, "accepts_snap");
            bool(appender, rows, "active_licence");
            for (String column : List.of("hours", "hours_source", "park_features", "inspection")) {
                text(appender, rows.getString(column));
            }
            OffsetDateTime inspected = rows.getObject("inspected_at", OffsetDateTime.class);
            if (inspected == null) {
                appender.appendNull();
            } else {
                appender.append(inspected);
            }
            text(appender, rows.getString("inspection_source"));
            text(appender, rows.getString("inspection_record_id"));
            text(appender, rows.getString("wkt"));
            appender.endRow();
            byCategory.merge(rows.getString("category"), 1, Integer::sum);
            count++;
        }
        return count;
    }

    private static int copySources(ResultSet rows, DuckDBAppender appender, Map<String, Integer> bySource,
            TreeSet<String> providers) throws SQLException {
        int count = 0;
        while (rows.next()) {
            appender.beginRow();
            appender.append(rows.getLong("place_id"));
            text(appender, rows.getString("source"));
            text(appender, rows.getString("source_key"));
            text(appender, rows.getString("method"));
            float score = rows.getFloat("score");
            if (rows.wasNull()) {
                appender.appendNull();
            } else {
                appender.append(score);
            }
            appender.endRow();
            bySource.merge(rows.getString("source"), 1, Integer::sum);
            String datasets = rows.getString("datasets");
            if (datasets != null) {
                Stream.of(datasets.split(";")).map(String::strip).filter(d -> !d.isEmpty()).forEach(providers::add);
            }
            count++;
        }
        return count;
    }

    static void write(Connection duck, Path folder) throws SQLException {
        try (var s = duck.createStatement()) {
            // DuckDB's built-in GEOMETRY type writes the GeoParquet metadata (WKB, CRS84, bbox) by itself.
            s.execute("COPY (SELECT * EXCLUDE (wkt), wkt::GEOMETRY AS geometry FROM places ORDER BY place_id) TO '"
                    + sql(folder.resolve(PLACES)) + "' (FORMAT parquet, COMPRESSION zstd)");
            s.execute("COPY (SELECT * FROM place_sources ORDER BY place_id, source, record_id) TO '"
                    + sql(folder.resolve(PLACE_SOURCES)) + "' (FORMAT parquet, COMPRESSION zstd)");
        }
    }

    private static void text(DuckDBAppender appender, String value) throws SQLException {
        if (value == null) {
            appender.appendNull();
        } else {
            appender.append(value);
        }
    }

    private static void bool(DuckDBAppender appender, ResultSet rows, String column) throws SQLException {
        boolean value = rows.getBoolean(column);
        if (rows.wasNull()) {
            appender.appendNull();
        } else {
            appender.append(value);
        }
    }

    static String sql(Path path) {
        return path.toAbsolutePath().toString().replace("'", "''");
    }

    static void deleteTree(Path path) throws IOException {
        FileSystemUtils.deleteRecursively(path);
    }
}
