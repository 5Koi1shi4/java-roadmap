package com.example.seckill.domain;

import java.util.Optional;
import java.time.Instant;

public interface SeckillRepository {
    int decrementStockIfAvailable(long productId);

    Optional<SeckillProduct> findProduct(long productId);

    SeckillOrder insertOrder(long userId, long productId);

    Optional<IdempotencyRecord> findByKey(String key);

    Optional<IdempotencyRecord> findByKeyForUpdate(String key);

    int takeOverProcessingIfExpired(String key, Instant expiredBefore);

    int insertProcessing(String key, String requestHash);

    void saveResponse(String key, int status, String body);
}
