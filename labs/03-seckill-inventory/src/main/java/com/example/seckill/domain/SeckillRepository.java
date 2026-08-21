package com.example.seckill.domain;

import java.util.Optional;

public interface SeckillRepository {
    int decrementStockIfAvailable(long productId);

    Optional<SeckillProduct> findProduct(long productId);

    SeckillOrder insertOrder(long userId, long productId);

    Optional<IdempotencyRecord> findByKey(String key);

    void insertProcessing(String key, String requestHash);

    void saveResponse(String key, int status, String body);
}
