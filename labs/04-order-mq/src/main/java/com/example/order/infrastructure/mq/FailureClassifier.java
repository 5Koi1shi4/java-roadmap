package com.example.order.infrastructure.mq;

import com.example.order.application.InvalidOrderEventException;
import com.example.order.domain.IllegalOrderTransitionException;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.amqp.AmqpConnectException;

import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;

/** Maps failures to the listener's two delivery policies. */
public class FailureClassifier {
    public enum Category {
        RETRYABLE,
        NON_RETRYABLE
    }

    public static final Category RETRYABLE = Category.RETRYABLE;
    public static final Category NON_RETRYABLE = Category.NON_RETRYABLE;

    public Category classify(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof RetryableMessageException
                    || current instanceof AmqpConnectException
                    || current instanceof TransientDataAccessException
                    || current instanceof CannotAcquireLockException
                    || current instanceof DeadlockLoserDataAccessException
                    || current instanceof PessimisticLockingFailureException
                    || current instanceof QueryTimeoutException
                    || current instanceof SQLTransactionRollbackException
                    || isTransientSqlFailure(current)) {
                return RETRYABLE;
            }
            if (current instanceof InvalidOrderEventException
                    || current instanceof JsonProcessingException
                    || current instanceof IllegalOrderTransitionException
                    || current instanceof NonRetryableMessageException) {
                return NON_RETRYABLE;
            }
            current = current.getCause();
        }
        return NON_RETRYABLE;
    }

    private boolean isTransientSqlFailure(Throwable failure) {
        if (!(failure instanceof SQLException sqlException)) {
            return false;
        }
        String state = sqlException.getSQLState();
        String message = sqlException.getMessage();
        return "40001".equals(state) || "41000".equals(state)
                || (message != null && (message.toLowerCase().contains("deadlock")
                || message.toLowerCase().contains("lock wait timeout")));
    }
}
