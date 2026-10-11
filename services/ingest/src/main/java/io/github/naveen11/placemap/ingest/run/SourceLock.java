package io.github.naveen11.placemap.ingest.run;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * "Only one run per source at a time" (ADR 0021): a Postgres session-level advisory lock held on a dedicated
 * connection for the whole run. If the process dies, even by kill -9, its connection drops and Postgres releases the
 * lock, so a crash never leaves a source locked. Needs a direct connection: session locks do not survive PgBouncer's
 * transaction pooling (ADR 0033).
 */
final class SourceLock implements AutoCloseable {

    private static final String KEY_PREFIX = "placemap.ingest:";

    private final Connection connection;
    private final String source;

    private SourceLock(Connection connection, String source) {
        this.connection = connection;
        this.source = source;
    }

    static Optional<SourceLock> tryAcquire(DataSource dataSource, String source) throws SQLException {
        Connection connection = dataSource.getConnection();
        try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))")) {
            statement.setString(1, KEY_PREFIX + source);
            try (var rs = statement.executeQuery()) {
                rs.next();
                if (rs.getBoolean(1)) {
                    return Optional.of(new SourceLock(connection, source));
                }
            }
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
        connection.close();
        return Optional.empty();
    }

    @Override
    public void close() throws SQLException {
        try (connection; var statement = connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
            statement.setString(1, KEY_PREFIX + source);
            statement.execute();
        }
    }
}
