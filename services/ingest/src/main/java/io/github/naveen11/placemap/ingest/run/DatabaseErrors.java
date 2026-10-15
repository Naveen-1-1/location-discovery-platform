package io.github.naveen11.placemap.ingest.run;

import java.sql.SQLException;
import java.util.List;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

/**
 * Which database errors another try can fix: a deadlock, a serialization failure, a dropped or refused connection,
 * as Spring classifies them. Looks through the whole chain of causes, because Spring Batch and the transaction manager
 * wrap them; a driver's own error is translated here the way Spring's JDBC classes would.
 *
 * <p>A transaction that could not begin, commit or roll back ({@link CannotCreateTransactionException},
 * {@link TransactionSystemException}) because its connection was closed counts too: the pool's error then carries no
 * SQL state to go by. With a state, the database's own error decides.
 */
final class DatabaseErrors {

    private static final SQLExceptionSubclassTranslator TRANSLATOR = new SQLExceptionSubclassTranslator();

    private DatabaseErrors() {
    }

    static boolean isTransient(Throwable failure) {
        List<Throwable> chain = ExceptionUtils.getThrowableList(failure);
        for (int i = 0; i < chain.size(); i++) {
            Throwable cause = chain.get(i);
            if (isTransientType(cause)) {
                return true;
            }
            if (cause instanceof SQLException e) {
                if (isTransientType(TRANSLATOR.translate("", null, e))) {
                    return true;
                }
                if (e.getSQLState() == null && i > 0 && isTransactionFailure(chain.get(i - 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isTransientType(Throwable t) {
        return t instanceof TransientDataAccessException || t instanceof RecoverableDataAccessException
                || t instanceof DataAccessResourceFailureException;
    }

    private static boolean isTransactionFailure(Throwable t) {
        return t instanceof CannotCreateTransactionException || t instanceof TransactionSystemException;
    }
}
