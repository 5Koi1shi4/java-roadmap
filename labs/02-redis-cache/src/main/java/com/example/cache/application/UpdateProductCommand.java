package com.example.cache.application;

public record UpdateProductCommand(long id, String name, long priceInCents) {

    public UpdateProductCommand {
        if (id <= 0) {
            throw new IllegalArgumentException("product id must be positive");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("product name must not be blank");
        }
        if (priceInCents < 0) {
            throw new IllegalArgumentException("product price must not be negative");
        }
    }
}
