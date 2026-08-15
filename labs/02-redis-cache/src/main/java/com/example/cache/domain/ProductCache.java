package com.example.cache.domain;

public interface ProductCache {

    CacheLookup get(long id);

    void put(Product product);

    void putNegative(long id);

    sealed interface CacheLookup permits CacheLookup.Miss, CacheLookup.ProductHit, CacheLookup.NegativeHit {

        record Miss() implements CacheLookup {
        }

        record ProductHit(Product product) implements CacheLookup {
        }

        record NegativeHit() implements CacheLookup {
        }

        static CacheLookup miss() {
            return new Miss();
        }

        static CacheLookup product(Product product) {
            return new ProductHit(product);
        }

        static CacheLookup negative() {
            return new NegativeHit();
        }
    }
}
