package com.example.campusmarket.order.application;

import com.example.campusmarket.order.domain.OrderStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 订单生命周期的共享规则。数据库写入由应用服务在事务中完成。 */
public final class OrderLifecycleService {
    private static final Set<String> EARLY_REASONS = Set.of(
        "QUANTITY", "MODEL", "APPEARANCE", "MISSING_PARTS", "NOT_AS_DESCRIBED", "FUNCTIONAL_DEFECT");

    private OrderLifecycleService() { }

    public static boolean isAllowedTransition(OrderStatus from, OrderStatus to) {
        if (from == null || to == null) return false;
        return switch (from) {
            case PENDING_PAYMENT -> EnumSet.of(OrderStatus.AWAITING_HANDOFF, OrderStatus.CANCELLED).contains(to);
            case AWAITING_HANDOFF -> EnumSet.of(OrderStatus.AWAITING_RECEIPT, OrderStatus.REFUNDING_CANCEL).contains(to);
            case AWAITING_RECEIPT -> EnumSet.of(OrderStatus.AFTERSALE_WINDOW, OrderStatus.DISPUTED).contains(to);
            case AFTERSALE_WINDOW -> EnumSet.of(OrderStatus.DISPUTED, OrderStatus.SETTLED).contains(to);
            case DISPUTED -> EnumSet.of(OrderStatus.AFTERSALE_WINDOW, OrderStatus.REFUNDED).contains(to);
            case REFUNDING_CANCEL -> to == OrderStatus.REFUNDED;
            case CANCELLED, REFUNDED, SETTLED -> false;
        };
    }

    public static boolean isReceiptConfirmationAllowed(OrderStatus status) {
        return status == OrderStatus.AWAITING_RECEIPT;
    }

    /** 使用数据库当前时间判断三天验收/七天试用窗口，边界为左闭右开。 */
    public static boolean isReasonAllowed(String reason, Instant t0, Instant databaseNow) {
        Objects.requireNonNull(t0, "T0不能为空");
        Objects.requireNonNull(databaseNow, "数据库时间不能为空");
        if (reason == null || t0.isAfter(databaseNow)) return false;
        String normalized = reason.trim().toUpperCase(Locale.ROOT);
        if (!EARLY_REASONS.contains(normalized) || !databaseNow.isBefore(t0.plus(Duration.ofDays(7)))) return false;
        return databaseNow.isBefore(t0.plus(Duration.ofHours(72))) || normalized.equals("FUNCTIONAL_DEFECT");
    }

    public static boolean isHandoffAllowed(OrderStatus status, Instant databaseNow, Instant deadline) {
        return status == OrderStatus.AWAITING_HANDOFF && databaseNow != null && deadline != null && databaseNow.isBefore(deadline);
    }

    public static boolean isHandoffTimeoutDue(OrderStatus status, Instant databaseNow, Instant deadline) {
        return status == OrderStatus.AWAITING_HANDOFF && databaseNow != null && deadline != null && !databaseNow.isBefore(deadline);
    }
}
