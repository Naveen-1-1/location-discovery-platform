package io.github.naveen11.placemap.ingest.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.util.backoff.BackOffExecution;

/** The writer's retry policy. Its writes themselves are covered by the container tests. */
class BatchWriterTest {

    @Test
    void writesAreRetriedOnlyForErrorsThatAnotherAttemptCanFix() {
        assertThat(retried(new SQLException("deadlock", "40P01"))).isTrue();
        assertThat(retried(new SQLException("serialization", "40001"))).isTrue();
        assertThat(retried(new SQLException("connection reset", "08006"))).isTrue();
        assertThat(retried(new SQLException("terminating connection", "57P01"))).isTrue();
        assertThat(retried(new SQLTransientConnectionException("pool timeout"))).isTrue();
        assertThat(retried(new SQLException("syntax error", "42601"))).isFalse();
        assertThat(retried(new SQLException("foreign key", "23503"))).isFalse();
    }

    @Test
    void errorsWrappedByTheTransactionManagerOrSpringBatchAreJudgedByTheirCause() {
        var dropped = new SQLException("An I/O error occurred while sending to the backend.", "08006");
        var syntax = new SQLException("syntax error", "42601");

        assertThat(BatchWriter.RETRY.shouldRetry(
                new CannotCreateTransactionException("Could not open JDBC Connection for transaction", dropped))).isTrue();
        assertThat(BatchWriter.RETRY.shouldRetry(new IllegalStateException("step failed", dropped))).isTrue();
        assertThat(BatchWriter.RETRY.shouldRetry(new IllegalStateException("step failed", syntax))).isFalse();
        assertThat(BatchWriter.RETRY.shouldRetry(
                new TransactionSystemException("JDBC rollback failed", new SQLException("Connection is closed")))).isTrue();
        assertThat(BatchWriter.RETRY.shouldRetry(
                new TransactionSystemException("JDBC commit failed", new SQLException("internal error", "XX000")))).isFalse();
    }

    /** As Spring's JDBC classes translate the error for Postgres, then the writer's retry policy judges it. */
    private static boolean retried(SQLException e) {
        var translator = new SQLErrorCodeSQLExceptionTranslator("PostgreSQL");
        return BatchWriter.RETRY.shouldRetry(translator.translate("test", null, e));
    }

    @Test
    void writeRetriesBackOffWithGrowingJitterFourTimesAtMost() {
        for (int i = 0; i < 100; i++) {
            BackOffExecution backOff = BatchWriter.RETRY.getBackOff().start();
            assertThat(backOff.nextBackOff()).isBetween(200L, 300L);
            assertThat(backOff.nextBackOff()).isBetween(200L, 600L);
            assertThat(backOff.nextBackOff()).isBetween(400L, 1_200L);
            assertThat(backOff.nextBackOff()).isBetween(800L, 2_400L);
            assertThat(backOff.nextBackOff()).isEqualTo(BackOffExecution.STOP);
        }
    }
}
