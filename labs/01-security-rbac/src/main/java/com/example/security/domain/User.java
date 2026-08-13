package com.example.security.domain;

import java.util.Objects;

public record User(long id, String username, String passwordHash, boolean enabled) {

    public User {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        Objects.requireNonNull(passwordHash, "passwordHash must not be null");
    }
}
