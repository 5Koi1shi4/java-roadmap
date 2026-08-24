package com.example.search.application.product;

import com.example.search.domain.Product;
import com.example.search.domain.ProductDetails;
import com.example.search.domain.ProductStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductView(long id, String name, String subtitle, String description,
                          String categoryCode, String categoryName, BigDecimal price,
                          ProductStatus status, long version, Instant createdAt, Instant updatedAt) {
    public static ProductView from(Product product) {
        ProductDetails d = product.details();
        return new ProductView(product.id(), d.name(), d.subtitle(), d.description(), d.categoryCode(),
                d.categoryName(), d.price(), d.status(), product.version(), product.createdAt(), product.updatedAt());
    }

    public ProductDetails details() {
        return ProductDetails.fromStored(name, subtitle, description, categoryCode, categoryName, price, status);
    }
}
