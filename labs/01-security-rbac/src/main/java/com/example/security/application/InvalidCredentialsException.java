package com.example.security.application;

public final class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("invalid username or password");
    }
}
