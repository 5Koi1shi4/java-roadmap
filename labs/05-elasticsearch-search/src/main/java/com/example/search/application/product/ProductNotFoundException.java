package com.example.search.application.product;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(long id) { super("product not found: " + id); }
}
