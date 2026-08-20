package com.example.cache.application;

import com.example.cache.domain.Product;

public record ProductView(boolean found, Product product) {

    public static ProductView found(Product product) {
        return new ProductView(true, product);
    }

    public static ProductView notFound() {
        return new ProductView(false, null);
    }
}
