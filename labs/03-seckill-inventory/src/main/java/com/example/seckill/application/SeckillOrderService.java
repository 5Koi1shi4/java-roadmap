package com.example.seckill.application;

import com.example.seckill.domain.SeckillOrder;
import com.example.seckill.domain.SeckillRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SeckillOrderService {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SeckillRepository repository;
    private final ConcurrentHashMap<String, ReentrantLock> idempotencyLocks = new ConcurrentHashMap<>();

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
            repository.restoreStock(productId);
            throw new AlreadyPurchasedException(userId, productId);
        }
    }

    @Transactional
    public IdempotentOrderResult placeOrder(String key, CreateOrderCommand command) {
        ReentrantLock lock = idempotencyLocks.computeIfAbsent(key, ignored -> new ReentrantLock());
        lock.lock();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    lock.unlock();
                }
            });
        } else {
            try {
                return placeOrderUnderKeyLock(key, command);
            } finally {
                lock.unlock();
            }
        }
        try {
            return placeOrderUnderKeyLock(key, command);
        } catch (RuntimeException | Error exception) {
            throw exception;
        }
    }

    private IdempotentOrderResult placeOrderUnderKeyLock(String key, CreateOrderCommand command) {
        String requestHash = requestHash(command.normalizedRequestBody());
        var existing = repository.findByKey(key);
        if (existing.isPresent()) {
            return resolveExisting(key, requestHash, existing.get());
        }
        try {
            repository.insertProcessing(key, requestHash);
        } catch (DuplicateKeyException duplicate) {
            return resolveExisting(key, requestHash, repository.findByKey(key)
                    .orElseThrow(() -> duplicate));
        }

        try {
            SeckillOrder order = placeOrder(command.userId(), command.productId());
            IdempotentOrderResult result = new IdempotentOrderResult(201, orderJson(order));
            repository.saveResponse(key, result.httpStatus(), result.responseBody());
            return persistedResult(key, result);
        } catch (ProductNotFoundException exception) {
            return saveBusinessError(key, 404, "PRODUCT_NOT_FOUND", exception.getMessage());
        } catch (SoldOutException exception) {
            return saveBusinessError(key, 409, "SOLD_OUT", exception.getMessage());
        } catch (AlreadyPurchasedException exception) {
            return saveBusinessError(key, 409, "ALREADY_PURCHASED", exception.getMessage());
        }
    }

    public String requestHash(String normalizedRequestBody) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalizedRequestBody.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private IdempotentOrderResult resolveExisting(String key, String requestHash,
                                                   com.example.seckill.domain.IdempotencyRecord record) {
        if (!record.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException(key);
        }
        if ("PROCESSING".equals(record.status())) {
            for (int attempt = 0; attempt < 500; attempt++) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
                var refreshed = repository.findByKey(key).orElse(record);
                if (!"PROCESSING".equals(refreshed.status())) {
                    return new IdempotentOrderResult(refreshed.responseStatus(), refreshed.responseBody());
                }
            }
            return new IdempotentOrderResult(409,
                    "{\"code\":\"REQUEST_IN_PROGRESS\",\"message\":\"请求正在处理中\"}");
        }
        return new IdempotentOrderResult(record.responseStatus(), record.responseBody());
    }

    private IdempotentOrderResult saveBusinessError(String key, int status, String code, String message) {
        ObjectNode response = JSON.createObjectNode();
        response.put("code", code);
        response.put("message", message);
        String body = writeJson(response);
        repository.saveResponse(key, status, body);
        return persistedResult(key, new IdempotentOrderResult(status, body));
    }

    private IdempotentOrderResult persistedResult(String key, IdempotentOrderResult fallback) {
        return repository.findByKey(key)
                .map(record -> new IdempotentOrderResult(record.responseStatus(), record.responseBody()))
                .orElse(fallback);
    }

    private String orderJson(SeckillOrder order) {
        ObjectNode response = JSON.createObjectNode();
        response.put("createdAt", order.createdAt().toString());
        response.put("id", order.id());
        response.put("message", "下单成功");
        response.put("productId", order.productId());
        response.put("userId", order.userId());
        return writeJson(response);
    }

    private String writeJson(ObjectNode value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize idempotent response", exception);
        }
    }
}
