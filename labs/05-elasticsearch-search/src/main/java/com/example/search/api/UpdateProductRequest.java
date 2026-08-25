package com.example.search.api;

import com.example.search.application.product.UpdateProductCommand;
import com.example.search.domain.ProductDetails;
import com.example.search.domain.ProductStatus;

import java.math.BigDecimal;

public record UpdateProductRequest(Long expectedVersion, String name, String subtitle, String description,
                                   String categoryCode, String categoryName, BigDecimal price, ProductStatus status) {
    UpdateProductCommand toCommand() {
        if (expectedVersion == null) throw new IllegalArgumentException("expectedVersion is required");
        return new UpdateProductCommand(expectedVersion, new ProductDetails(name, subtitle, description,
                categoryCode, categoryName, price, status));
    }
}
