package com.example.seckill.application;

public class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException(String key) {
        super("Idempotency key was reused with a different request: " + key);
    }
}
