package com.example.campusmarket.order.domain;

import java.util.EnumSet;
import java.util.Set;

/** 订单状态机唯一允许的业务迁移集合，供应用层与持久化层共同校验。 */
public final class OrderStatusTransitions {
    private OrderStatusTransitions() {}

    public static boolean isAllowed(OrderStatus from, OrderStatus to) {
        if (from == null || to == null) return false;
        return allowed(from).contains(to);
    }

    private static Set<OrderStatus> allowed(OrderStatus from) {
        return switch (from) {
            case PENDING_PAYMENT -> EnumSet.of(OrderStatus.AWAITING_HANDOFF, OrderStatus.CANCELLED);
            case AWAITING_HANDOFF -> EnumSet.of(OrderStatus.AWAITING_RECEIPT, OrderStatus.REFUNDING_CANCEL);
            case AWAITING_RECEIPT -> EnumSet.of(OrderStatus.AFTERSALE_WINDOW, OrderStatus.DISPUTED);
            case AFTERSALE_WINDOW -> EnumSet.of(OrderStatus.DISPUTED, OrderStatus.SETTLED);
            case DISPUTED -> EnumSet.of(OrderStatus.AFTERSALE_WINDOW, OrderStatus.REFUNDED);
            case REFUNDING_CANCEL -> EnumSet.of(OrderStatus.REFUNDED);
            case CANCELLED, REFUNDED, SETTLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }
}
