package com.example.search.api;

import com.example.search.application.product.CreateProductCommand;
import com.example.search.domain.ProductDetails;
import com.example.search.domain.ProductStatus;

import java.math.BigDecimal;

public record CreateProductRequest(String name, String subtitle, String description, String categoryCode,
                                   String categoryName, BigDecimal price, ProductStatus status) {
    CreateProductCommand toCommand() {
        return new CreateProductCommand(new ProductDetails(name, subtitle, description, categoryCode,
                categoryName, price, status));
    }
}
