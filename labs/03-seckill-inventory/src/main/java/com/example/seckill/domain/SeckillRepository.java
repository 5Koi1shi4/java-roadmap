package com.example.seckill.domain;

import java.util.Optional;

public interface SeckillRepository {
    int decrementStockIfAvailable(long productId);

    Optional<SeckillProduct> findProduct(long productId);

    SeckillOrder insertOrder(long userId, long productId);
}
