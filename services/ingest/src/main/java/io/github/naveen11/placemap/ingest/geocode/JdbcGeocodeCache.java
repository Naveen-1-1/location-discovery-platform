package io.github.naveen11.placemap.ingest.geocode;

import java.sql.Types;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code ingest.geocode_cache}. Writes commit in a transaction of their own, outside any batch's: a decision paid for
 * with a Photon call must survive the batch rolling back, so the redone batch finds it.
 */
public final class JdbcGeocodeCache implements GeocodeCache {

    private static final String INSERT = """
            INSERT INTO ingest.geocode_cache (query, lon, lat, quality, detail) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (query) DO NOTHING
            """;

    private final JdbcClient jdbc;
    private final JdbcTemplate batches;
    private final TransactionTemplate ownTransaction;

    public JdbcGeocodeCache(DataSource dataSource, PlatformTransactionManager transactions) {
        this.jdbc = JdbcClient.create(dataSource);
        this.batches = new JdbcTemplate(dataSource);
        this.ownTransaction = new TransactionTemplate(transactions);
        ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
        // Sorted, so concurrent batches insert in the same order (fewer deadlocks).
        List<Object[]> rows = decisions.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> new Object[] {e.getKey(), e.getValue().lon(), e.getValue().lat(), e.getValue().quality(),
                        e.getValue().detail()})
                .toList();
        ownTransaction.executeWithoutResult(status -> batches.batchUpdate(INSERT, rows,
                new int[] {Types.VARCHAR, Types.DOUBLE, Types.DOUBLE, Types.VARCHAR, Types.VARCHAR}));
    }
}
