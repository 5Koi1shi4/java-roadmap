package com.example.search.domain;

import java.math.BigDecimal;
import java.util.Objects;

public final class ProductDetails {
    private final String name;
    private final String subtitle;
    private final String description;
    private final String categoryCode;
    private final String categoryName;
    private final BigDecimal price;
    private final ProductStatus status;

    public ProductDetails(String name, String subtitle, String description,
                          String categoryCode, String categoryName, BigDecimal price,
                          ProductStatus status) {
        this(name, subtitle, description, categoryCode, categoryName, price, status, false);
    }

    private ProductDetails(String name, String subtitle, String description,
                           String categoryCode, String categoryName, BigDecimal price,
                           ProductStatus status, boolean stored) {
        this.name = boundedText(name, "name", 120, true);
        this.subtitle = boundedText(subtitle, "subtitle", 255, false);
        this.description = requireText(description, "description");
        this.categoryCode = boundedText(categoryCode, "categoryCode", 64, true);
        this.categoryName = boundedText(categoryName, "categoryName", 120, true);
        if (price == null || price.signum() < 0 || price.scale() > 2) {
            throw new IllegalArgumentException("price must be non-negative with at most 2 decimals");
        }
        if (status == null || (!stored && status == ProductStatus.DELETED)) {
            throw new IllegalArgumentException("creation status must be ON_SALE or OFF_SHELF");
        }
        this.price = price;
        this.status = status;
    }

    public static ProductDetails fromStored(String name, String subtitle, String description,
                                             String categoryCode, String categoryName, BigDecimal price,
                                             ProductStatus status) {
        return new ProductDetails(name, subtitle, description, categoryCode, categoryName, price, status, true);
    }

    public String name() { return name; }
    public String subtitle() { return subtitle; }
    public String description() { return description; }
    public String categoryCode() { return categoryCode; }
    public String categoryName() { return categoryName; }
    public BigDecimal price() { return price; }
    public ProductStatus status() { return status; }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String boundedText(String value, String field, int max, boolean required) {
        if (value == null) {
            if (required) throw new IllegalArgumentException(field + " is required");
            return null;
        }
        String normalized = value.trim();
        if (required && normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        if (normalized.length() > max) throw new IllegalArgumentException(field + " must be at most " + max + " characters");
        return normalized;
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof ProductDetails other)) return false;
        return Objects.equals(name, other.name) && Objects.equals(subtitle, other.subtitle)
                && Objects.equals(description, other.description) && Objects.equals(categoryCode, other.categoryCode)
                && Objects.equals(categoryName, other.categoryName) && Objects.equals(price, other.price)
                && status == other.status;
    }
    @Override public int hashCode() { return Objects.hash(name, subtitle, description, categoryCode, categoryName, price, status); }
}
