package com.example.cache.application;

import com.example.cache.domain.Product;
import com.example.cache.domain.ProductCache;
import com.example.cache.domain.ProductCache.CacheLookup;
import com.example.cache.domain.ProductRepository;

public final class ProductQueryService {

    private final ProductRepository repository;
    private final ProductCache cache;

    public ProductQueryService(ProductRepository repository, ProductCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    public ProductView getProduct(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("product id must be positive");
        }

        CacheLookup cached = cache.get(id);
        if (cached instanceof CacheLookup.ProductHit productHit) {
            return ProductView.found(productHit.product());
        }
        if (cached instanceof CacheLookup.NegativeHit) {
            return ProductView.notFound();
        }

        return repository.findById(id)
                .map(this::cacheAndReturn)
                .orElseGet(() -> cacheNegativeAndReturnNotFound(id));
    }

    private ProductView cacheAndReturn(Product product) {
        cache.put(product);
        return ProductView.found(product);
    }

    private ProductView cacheNegativeAndReturnNotFound(long id) {
        cache.putNegative(id);
        return ProductView.notFound();
    }
}
