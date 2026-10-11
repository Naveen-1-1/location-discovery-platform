package io.github.naveen11.placemap.ingest.run;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "Only one run per source at a time" (ADR 0021): a Postgres session-level advisory lock held on a dedicated
 * connection for the whole run. If the process dies, even by kill -9, its connection drops and Postgres releases the
 * lock, so a crash never leaves a source locked. Needs a direct connection: session locks do not survive PgBouncer's
 * transaction pooling (ADR 0033).
 *
 * <p>The same goes for a dropped connection while the process lives on (a network cut, crash test C2): Postgres
 * releases the lock silently. So the run's completion transaction runs on this connection ({@link #connection()}): if
 * the connection is gone, the completion fails with it and the run stays open until a process holds the lock again.
 */
final class SourceLock implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SourceLock.class);

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

    /** The connection that holds the lock; work done in a transaction on it can only commit while the lock is held. */
    Connection connection() {
        return connection;
    }

    @Override
    public void close() {
        try (connection; var statement = connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
            statement.setString(1, KEY_PREFIX + source);
            statement.execute();
        } catch (SQLException e) {
            // Closing the connection ends the session, and Postgres releases the lock with it.
            log.warn("ingest {}: could not release the source lock ({}); it is released with its connection", source,
                    e.toString());
        }
    }
}
