package com.example.search.api;

import com.example.search.application.product.ProductView;
import com.example.search.domain.ProductStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(long id, String name, String subtitle, String description, String categoryCode,
                              String categoryName, BigDecimal price, ProductStatus status, long version,
                              Instant createdAt, Instant updatedAt) {
    static ProductResponse from(ProductView view) {
        return new ProductResponse(view.id(), view.name(), view.subtitle(), view.description(), view.categoryCode(),
                view.categoryName(), view.price(), view.status(), view.version(), view.createdAt(), view.updatedAt());
    }
}
