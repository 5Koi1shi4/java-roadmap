package com.example.search.application.product;

import com.example.search.domain.Product;
import com.example.search.domain.ProductDetails;

import java.time.Instant;
import java.util.Optional;

public interface ProductRepository {
    Product insert(ProductDetails details, Instant now);
    int updateIfVersionMatches(long id, long expectedVersion, ProductDetails details, Instant now);
    int markDeletedIfVersionMatches(long id, long expectedVersion, Instant now);
    Optional<Product> findById(long id);
}
