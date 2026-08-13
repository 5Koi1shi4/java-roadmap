package com.example.security.application;

public final class UserDisabledException extends RuntimeException {

    public UserDisabledException() {
        super("user is disabled");
    }
}
