package com.example.search.application.product;

import com.example.search.domain.ProductDetails;

public record UpdateProductCommand(long expectedVersion, ProductDetails details) {
    public UpdateProductCommand(ProductDetails details, long expectedVersion) {
        this(expectedVersion, details);
    }
    public UpdateProductCommand {
        if (expectedVersion <= 0) throw new IllegalArgumentException("expectedVersion must be positive");
        if (details == null) throw new IllegalArgumentException("details is required");
    }
}
