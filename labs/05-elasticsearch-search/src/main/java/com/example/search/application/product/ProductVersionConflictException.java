package com.example.search.application.product;

public class ProductVersionConflictException extends RuntimeException {
    public ProductVersionConflictException(long id, long expectedVersion) {
        super("product version conflict: id=" + id + ", expectedVersion=" + expectedVersion);
    }
}
