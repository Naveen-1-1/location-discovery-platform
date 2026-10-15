package io.github.naveen11.placemap.ingest.run;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Data sources over a real one whose n-th commit, counted across all their connections, fails the way a crash or a
 * cut connection makes it fail. {@link TestIngest} keeps Spring Batch's own tables on the real data source, so only
 * the import's commits count.
 */
final class CommitFaults {

    private interface Fault {
        void apply(Connection real) throws SQLException;
    }

    private CommitFaults() {
    }

    /** The n-th commit rolls back and throws instead, as if the process died there. */
    static DataSource failingOnCommit(DataSource real, int n) {
        return onCommit(real, n, connection -> {
            connection.rollback();
            throw new SQLException("injected commit failure", "XX000");
        });
    }

    /** The n-th commit goes through, then throws as if the connection dropped before the reply. */
    static DataSource losingCommitAcknowledgement(DataSource real, int n) {
        return onCommit(real, n, connection -> {
            connection.commit();
            throw new SQLException("injected: connection lost after COMMIT was sent", "08006");
        });
    }

    /** Commits {@code first} to {@code last} roll back and throw as if the connection dropped: a long outage. */
    static DataSource droppingConnectionOnCommits(DataSource real, int first, int last) {
        return onCommits(real, first, last, connection -> {
            connection.rollback();
            throw new SQLException("injected: connection lost before COMMIT", "08006");
        });
    }

    private static DataSource onCommit(DataSource real, int n, Fault fault) {
        return onCommits(real, n, n, fault);
    }

    private static DataSource onCommits(DataSource real, int first, int last, Fault fault) {
        var commits = new AtomicInteger();
        return new DelegatingDataSource(real) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                var proxy = new ProxyFactory(connection);
                proxy.addInterface(Connection.class);
                proxy.addAdvice((MethodInterceptor) call -> {
                    if (call.getMethod().getName().equals("commit")) {
                        int n = commits.incrementAndGet();
                        if (n >= first && n <= last) {
                            fault.apply(connection);
                            return null;
                        }
                    }
                    return call.proceed();
                });
                return (Connection) proxy.getProxy();
            }
        };
    }
}
