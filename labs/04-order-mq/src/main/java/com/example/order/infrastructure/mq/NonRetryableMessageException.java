package com.example.order.infrastructure.mq;

/** Signals a malformed or otherwise permanently invalid message. */
public class NonRetryableMessageException extends RuntimeException {
    public NonRetryableMessageException(String message) {
        super(message);
    }

    public NonRetryableMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
