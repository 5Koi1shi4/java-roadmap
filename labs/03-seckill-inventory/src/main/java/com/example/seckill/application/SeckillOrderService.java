package com.example.seckill.application;

import com.example.seckill.domain.SeckillOrder;
import com.example.seckill.domain.SeckillRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

@Service
public class SeckillOrderService {
    private final SeckillRepository repository;

    public SeckillOrderService(SeckillRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public SeckillOrder placeOrder(long userId, long productId) {
        repository.findProduct(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        if (repository.decrementStockIfAvailable(productId) == 0) {
            throw new SoldOutException(productId);
        }
        try {
            return repository.insertOrder(userId, productId);
        } catch (DuplicateKeyException e) {
            throw new AlreadyPurchasedException(userId, productId);
        }
    }
}
