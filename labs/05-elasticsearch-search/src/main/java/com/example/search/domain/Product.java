package com.example.search.domain;

import java.time.Instant;

public record Product(long id, ProductDetails details, long version, Instant createdAt, Instant updatedAt) {
    public Product {
        if (id <= 0) throw new IllegalArgumentException("id must be positive");
        if (details == null) throw new IllegalArgumentException("details is required");
        if (version <= 0) throw new IllegalArgumentException("version must be positive");
        if (createdAt == null || updatedAt == null) throw new IllegalArgumentException("timestamps are required");
    }
}
