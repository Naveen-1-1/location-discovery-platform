package io.github.naveen11.placemap.ingest.geocode;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code ingest.geocode_cache}. Writes commit on their own, outside any batch's transaction: a decision paid for with
 * a Photon call must survive the batch rolling back, so the redone batch finds it.
 */
public final class JdbcGeocodeCache implements GeocodeCache {

    private static final String INSERT = """
            INSERT INTO ingest.geocode_cache (query, lon, lat, quality, detail) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (query) DO NOTHING
            """;

    private final DataSource dataSource;
    private final JdbcClient jdbc;

    public JdbcGeocodeCache(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public Map<String, Geocode> lookup(Collection<String> queries) {
        var found = new HashMap<String, Geocode>();
        if (queries.isEmpty()) {
            return found;
        }
        jdbc.sql("SELECT query, lon, lat, quality, detail FROM ingest.geocode_cache WHERE query = ANY (?)")
                .param(queries.toArray(String[]::new))
                .query(rs -> {
                    double lon = rs.getDouble("lon");
                    Double located = rs.wasNull() ? null : lon;
                    found.put(rs.getString("query"), new Geocode(located, located == null ? null : rs.getDouble("lat"),
                            rs.getString("quality"), rs.getString("detail")));
                });
        return found;
    }

    @Override
    public void store(Map<String, Geocode> decisions) {
        if (decisions.isEmpty()) {
            return;
        }
        try (Connection c = dataSource.getConnection(); var insert = c.prepareStatement(INSERT)) {
            c.setAutoCommit(true);
            for (var entry : decisions.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                Geocode g = entry.getValue();
                insert.setString(1, entry.getKey());
                if (g.located()) {
                    insert.setDouble(2, g.lon());
                    insert.setDouble(3, g.lat());
                } else {
                    insert.setNull(2, Types.DOUBLE);
                    insert.setNull(3, Types.DOUBLE);
                }
                insert.setString(4, g.quality());
                insert.setString(5, g.detail());
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("could not store geocodes", e);
        }
    }
}
