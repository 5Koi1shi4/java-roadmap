package com.example.order.infrastructure.mq;

/** Signals a transient failure that should be retried by the AMQP listener. */
public class RetryableMessageException extends RuntimeException {
    public RetryableMessageException(String message) {
        super(message);
    }

    public RetryableMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
