package com.example.security.domain;

public record Role(long id, String code, String name) {

    public Role {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
