package com.example.cache.domain;

import java.util.Optional;

public interface ProductRepository {

    Optional<Product> findById(long id);
}
