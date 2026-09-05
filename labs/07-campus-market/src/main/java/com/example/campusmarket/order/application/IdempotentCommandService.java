package com.example.campusmarket.order.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** 在下单事务中按 actor 锁定并完成幂等命令。 */
@Service
public class IdempotentCommandService {
    private final com.example.campusmarket.order.infrastructure.JdbcOrderRepository repository;
    private final OrderCreationHook hook;

    public IdempotentCommandService(com.example.campusmarket.order.infrastructure.JdbcOrderRepository repository,
                                    ObjectMapper objectMapper, OrderCreationHook hook) {
        this.repository = Objects.requireNonNull(repository, "订单仓储不能为空");
        Objects.requireNonNull(objectMapper, "JSON序列化器不能为空");
        this.hook = Objects.requireNonNull(hook, "订单钩子不能为空");
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
        hook.beforeCommand(actorId, idempotencyKey);
        hook.beforeCommandLockAttempt(actorId, idempotencyKey);
        var commandLock = repository.lockOrCreateCommand(actorId, idempotencyKey, digest);
        hook.afterCommandLocked(actorId, idempotencyKey);
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

    /** 复用订单命令表为交付/收货命令提供同样的 actor+key fencing。 */
    @Transactional
    public byte[] executeLifecycle(UUID actorId, String idempotencyKey, byte[] request,
                                   UUID orderId, Supplier<byte[]> action) {
        if (actorId == null || idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 191)
            throw new IllegalArgumentException("Idempotency-Key无效");
        Objects.requireNonNull(request, "请求不能为空");
        Objects.requireNonNull(orderId, "订单ID不能为空");
        Objects.requireNonNull(action, "命令动作不能为空");
        byte[] digest;
        try { digest = MessageDigest.getInstance("SHA-256").digest(request); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256不可用", e); }
        hook.beforeCommand(actorId, idempotencyKey);
        var lock = repository.lockOrCreateCommand(actorId, idempotencyKey, digest);
        if (!MessageDigest.isEqual(lock.requestHash(), digest)) throw new IdempotencyConflictException();
        if (lock.completed()) return lock.responseUtf8();
        byte[] response = action.get();
        repository.completeCommand(lock.id(), response, orderId);
        return response;
    }

    public static final class IdempotencyConflictException extends RuntimeException { }
}
