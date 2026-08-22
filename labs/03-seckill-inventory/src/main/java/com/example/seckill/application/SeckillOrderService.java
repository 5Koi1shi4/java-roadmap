package com.example.seckill.application;

import com.example.seckill.domain.SeckillOrder;
import com.example.seckill.domain.SeckillRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.Instant;

@Service
public class SeckillOrderService {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SeckillRepository repository;
    private final TransactionTemplate businessTransaction;
    private static final long PROCESSING_TIMEOUT_SECONDS = 60;

    public SeckillOrderService(SeckillRepository repository) {
        this(repository, null);
    }

    @Autowired
    public SeckillOrderService(SeckillRepository repository,
                               org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.businessTransaction = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        if (this.businessTransaction != null) {
            this.businessTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
    }

    @Transactional
    public SeckillOrder placeOrder(long userId, long productId) {
        if (businessTransaction != null) {
            return businessTransaction.execute(status -> placeOrderInTransaction(userId, productId));
        }
        return placeOrderInTransaction(userId, productId);
    }

    private SeckillOrder placeOrderInTransaction(long userId, long productId) {
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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IdempotentOrderResult placeOrder(String key, CreateOrderCommand command) {
        String requestHash = requestHash(command.normalizedRequestBody());
        var existing = repository.findByKey(key);
        boolean inserted = existing.isEmpty() && repository.insertProcessing(key, requestHash) == 1;
        var terminal = repository.findByKeyForUpdate(key);
        if (terminal.isEmpty() && existing.isPresent()) {
            terminal = existing;
        }
        if (terminal.isEmpty() && !inserted) {
            inserted = repository.insertProcessing(key, requestHash) == 1;
            terminal = repository.findByKeyForUpdate(key);
        }
        if (terminal.isEmpty() && inserted) {
            terminal = java.util.Optional.of(new com.example.seckill.domain.IdempotencyRecord(
                    key, requestHash, "PROCESSING", null, null, Instant.now(), Instant.now()));
        }
        if (terminal.isEmpty()) {
            throw new IllegalStateException("Idempotency record disappeared during processing");
        }
        var record = terminal.get();
        if (!record.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException(key);
        }
        if ("SUCCEEDED".equals(record.status())) {
            return new IdempotentOrderResult(record.responseStatus(), record.responseBody());
        }
        if (!inserted && repository.takeOverProcessingIfExpired(
                key, Instant.now().minusSeconds(PROCESSING_TIMEOUT_SECONDS)) == 0) {
            throw new IllegalStateException("Idempotency record remains PROCESSING");
        }

        try {
            SeckillOrder order = placeOrder(command.userId(), command.productId());
            IdempotentOrderResult result = new IdempotentOrderResult(201, orderJson(order));
            repository.saveResponse(key, result.httpStatus(), result.responseBody());
            return result;
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

    private IdempotentOrderResult saveBusinessError(String key, int status, String code, String message) {
        ObjectNode response = JSON.createObjectNode();
        response.put("code", code);
        response.put("message", message);
        String body = writeJson(response);
        repository.saveResponse(key, status, body);
        return new IdempotentOrderResult(status, body);
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
