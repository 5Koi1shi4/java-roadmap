package com.example.campusmarket.order.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** 在下单事务中按 actor 锁定并完成幂等命令。 */
@Service
public class IdempotentCommandService {
    private final com.example.campusmarket.order.infrastructure.JdbcOrderRepository repository;

    public IdempotentCommandService(com.example.campusmarket.order.infrastructure.JdbcOrderRepository repository,
                                    ObjectMapper objectMapper) {
        this.repository = Objects.requireNonNull(repository, "订单仓储不能为空");
        Objects.requireNonNull(objectMapper, "JSON序列化器不能为空");
    }

    @Transactional
    public CreateOrderService.CreateOrderResult execute(UUID actorId, String idempotencyKey,
                                                         CreateOrderCommand command,
                                                         Supplier<CreateOrderService.CreateOrderResult> action) {
        if (actorId == null) throw new IllegalArgumentException("买家不能为空");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 191) {
            throw new IllegalArgumentException("Idempotency-Key无效");
        }
        Objects.requireNonNull(command, "下单命令不能为空");
        Objects.requireNonNull(action, "下单动作不能为空");
        byte[] digest = requestDigest(command);
        var commandLock = repository.lockOrCreateCommand(actorId, idempotencyKey, digest);
        if (!MessageDigest.isEqual(commandLock.requestHash(), digest)) {
            throw new IdempotencyConflictException();
        }
        if (commandLock.completed()) {
            return new CreateOrderService.CreateOrderResult(201, commandLock.responseUtf8());
        }
        CreateOrderService.CreateOrderResult result = action.get();
        repository.completeCommand(commandLock.id(), result.responseUtf8(), result.orderId());
        return result;
    }

    public static byte[] requestDigest(CreateOrderCommand command) {
        String canonical = "{\"api\":\"POST /api/orders\",\"version\":1,\"listingId\":\""
            + command.listingId() + "\",\"quantity\":" + command.quantity() + "}";
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256不可用", e);
        }
    }

    public static final class IdempotencyConflictException extends RuntimeException { }
}
