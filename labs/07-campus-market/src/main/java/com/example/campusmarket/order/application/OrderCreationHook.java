package com.example.campusmarket.order.application;

import java.util.UUID;

/** 事务中段故障注入端口，生产环境使用空实现。 */
public interface OrderCreationHook {
    void afterInventoryDeducted(UUID orderId);
}
