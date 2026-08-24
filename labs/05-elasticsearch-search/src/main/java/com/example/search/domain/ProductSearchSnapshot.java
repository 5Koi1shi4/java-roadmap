package com.example.search.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductSearchSnapshot(long productId, long sourceVersion, String name, String subtitle,
                                    String description, String categoryCode, String categoryName,
                                    BigDecimal price, ProductStatus status, Instant createdAt, Instant updatedAt) {
    public ProductSearchSnapshot {
        if (productId <= 0) throw new IllegalArgumentException("productId must be positive");
        if (sourceVersion <= 0) throw new IllegalArgumentException("sourceVersion must be positive");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("description is required");
        if (categoryCode == null || categoryCode.isBlank()) throw new IllegalArgumentException("categoryCode is required");
        if (categoryName == null || categoryName.isBlank()) throw new IllegalArgumentException("categoryName is required");
        if (price == null || price.signum() < 0 || price.scale() > 2) throw new IllegalArgumentException("invalid price");
        if (status == null) throw new IllegalArgumentException("status is required");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }

    public static ProductSearchSnapshot from(Product product) {
        ProductDetails d = product.details();
        return new ProductSearchSnapshot(product.id(), product.version(), d.name(), d.subtitle(), d.description(),
                d.categoryCode(), d.categoryName(), d.price(), product.details().status(), product.createdAt(), product.updatedAt());
    }
}
