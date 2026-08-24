package com.example.search.application.product;

import com.example.search.domain.ProductDetails;
import com.example.search.domain.ProductStatus;

import java.math.BigDecimal;

public record CreateProductCommand(ProductDetails details) {
    public CreateProductCommand {
        if (details == null) throw new IllegalArgumentException("details is required");
    }

    public CreateProductCommand(String name, String subtitle, String description,
                                String categoryCode, String categoryName, BigDecimal price,
                                ProductStatus status) {
        this(new ProductDetails(name, subtitle, description, categoryCode, categoryName, price, status));
    }
}
