package com.example.campusmarket.order.application;

import java.util.UUID;

/** 事务中段故障注入端口，生产环境使用空实现。 */
public interface OrderCreationHook {
    default void beforeCommand(UUID actorId, String idempotencyKey) { }

    /** 仅用于测试在进入数据库命令锁尝试前建立可观测边界；生产实现保持空操作。 */
    default void beforeCommandLockAttempt(UUID actorId, String idempotencyKey) { }

    default void afterCommandLocked(UUID actorId, String idempotencyKey) { }

    void afterInventoryDeducted(UUID orderId);

    default void afterOrderCreatedOutbox(UUID orderId) { }
}
