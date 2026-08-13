package com.example.security.application;

public final class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(Throwable cause) {
        super("token is invalid or expired", cause);
    }
}
