package com.example.campusmarket.order.application;

import com.example.campusmarket.order.domain.OrderStatus;
import com.example.campusmarket.order.domain.OrderStatusTransitions;
import com.example.campusmarket.catalog.application.InventoryPort;
import com.example.campusmarket.order.infrastructure.JdbcOrderLifecycleRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 订单生命周期的共享规则。数据库写入由应用服务在事务中完成。 */
@Service
@Profile("!test")
public final class OrderLifecycleService {
    private static final Set<String> EARLY_REASONS = Set.of(
        "QUANTITY", "MODEL", "APPEARANCE", "MISSING_PARTS", "NOT_AS_DESCRIBED", "FUNCTIONAL_DEFECT");

    private final JdbcOrderLifecycleRepository repository;
    private final InventoryPort inventory;
    private final TransactionTemplate transactions;

    public OrderLifecycleService(JdbcOrderLifecycleRepository repository, InventoryPort inventory,
                                 org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.repository = Objects.requireNonNull(repository, "订单生命周期仓储不能为空");
        this.inventory = Objects.requireNonNull(inventory, "库存端口不能为空");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
    }

    /** 卖家在交付截止前确认交付；状态、交付记录和事件在一个事务中完成。 */
    public boolean handoff(java.util.UUID orderId, java.util.UUID sellerId, String note) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var row = repository.lock(orderId);
            Instant now = repository.databaseNow();
            if (row == null) throw new OrderNotFoundException();
            if (!row.sellerId().equals(sellerId)) throw new OrderNotFoundException();
            if (!isHandoffAllowed(row.status(), now, row.handoffDeadline())) return false;
            return repository.markHandoff(orderId, sellerId, row.version(), now, note, now.plus(Duration.ofHours(48))) == 1;
        }));
    }

    /** 买家在收货截止前确认；T0 与两个窗口只会在 CAS 成功时写入一次。 */
    public boolean confirmReceipt(java.util.UUID orderId, java.util.UUID buyerId) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var row = repository.lock(orderId);
            if (row == null) throw new OrderNotFoundException();
            if (!row.buyerId().equals(buyerId)) throw new OrderNotFoundException();
            if (!isReceiptConfirmationAllowed(row.status())) return false;
            Instant now = repository.databaseNow();
            if (row.receiptDeadline() == null || !now.isBefore(row.receiptDeadline())) return false;
            return repository.confirmReceipt(orderId, buyerId, row.version(), now, now.plus(Duration.ofHours(72)), now.plus(Duration.ofDays(7))) == 1;
        }));
    }

    /** 买家在试用窗口内发起争议；仅记录生命周期边界，争议处理由 Task10 负责。 */
    public boolean openDispute(java.util.UUID orderId, java.util.UUID buyerId, String reason) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var row = repository.lock(orderId);
            if (row == null) throw new OrderNotFoundException();
            if (!row.buyerId().equals(buyerId)) throw new OrderNotFoundException();
            Instant now = repository.databaseNow();
            if (row.t0() == null || !isReasonAllowed(reason, row.t0(), now)) return false;
            return repository.transition(orderId, row.status(), OrderStatus.DISPUTED, row.version(), now,
                null, false, null, null, null, "BUYER_DISPUTE") == 1;
        }));
    }

    public boolean expirePayment(java.util.UUID orderId, JdbcOrderLifecycleRepository.DeadlineClaim claim) {
        return runClaim(claim, orderId, (row, now) -> {
            if (row.status() != OrderStatus.PENDING_PAYMENT || row.paymentDeadline() == null || now.isBefore(row.paymentDeadline())) return false;
            int changed = repository.transition(orderId, row.status(), OrderStatus.CANCELLED, row.version(), now,
                "payment_deadline", false, null, null, null, "PAYMENT_TIMEOUT");
            if (changed == 1 && !inventory.restore(row.listingId(), row.quantity(), "order:" + orderId + ":payment-timeout"))
                throw new IllegalStateException("支付超时返还库存失败");
            return changed == 1;
        });
    }

    public boolean expireHandoff(java.util.UUID orderId, JdbcOrderLifecycleRepository.DeadlineClaim claim) {
        return runClaim(claim, orderId, (row, now) -> {
            if (row.status() != OrderStatus.AWAITING_HANDOFF || row.handoffDeadline() == null || now.isBefore(row.handoffDeadline())) return false;
            int changed = repository.transition(orderId, row.status(), OrderStatus.REFUNDING_CANCEL, row.version(), now,
                "handoff_deadline", false, null, null, null, "HANDOFF_TIMEOUT");
            if (changed == 1 && !inventory.restore(row.listingId(), row.quantity(), "order:" + orderId + ":handoff-timeout"))
                throw new IllegalStateException("交付超时返还库存失败");
            return changed == 1;
        });
    }

    public boolean autoConfirmReceipt(java.util.UUID orderId, JdbcOrderLifecycleRepository.DeadlineClaim claim) {
        return runClaim(claim, orderId, (row, now) -> {
            if (row.status() != OrderStatus.AWAITING_RECEIPT || row.receiptDeadline() == null || now.isBefore(row.receiptDeadline())) return false;
            return repository.confirmReceiptAutomatically(orderId, row.version(), now, now.plus(Duration.ofHours(72)), now.plus(Duration.ofDays(7))) == 1;
        });
    }

    /** 七天边界只关闭普通订单生命周期；真实 settlement 由 Task11 创建。 */
    public boolean closeTrial(java.util.UUID orderId, JdbcOrderLifecycleRepository.DeadlineClaim claim) {
        return runClaim(claim, orderId, (row, now) -> {
            if (row.status() != OrderStatus.AFTERSALE_WINDOW || row.trialDeadline() == null || now.isBefore(row.trialDeadline())) return false;
            return repository.markTrialElapsed(orderId, row.version(), now) == 1;
        });
    }

    private boolean runClaim(java.util.UUID orderId, JdbcOrderLifecycleRepository.DeadlineClaim claim, DeadlineAction action) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            if (claim == null || !orderId.equals(claim.orderId())) return false;
            var row = repository.lock(orderId);
            if (row == null) return false;
            Instant now = repository.databaseNow();
            if (!repository.lockOwnedClaim(claim, now)) return false;
            boolean changed = action.apply(row, now);
            repository.completeClaim(claim, now);
            return changed;
        }));
    }

    private boolean runClaim(JdbcOrderLifecycleRepository.DeadlineClaim claim, java.util.UUID orderId, DeadlineAction action) {
        return runClaim(orderId, claim, action);
    }

    @FunctionalInterface
    private interface DeadlineAction { boolean apply(JdbcOrderLifecycleRepository.OrderRow row, Instant now); }

    public static boolean isAllowedTransition(OrderStatus from, OrderStatus to) {
        return OrderStatusTransitions.isAllowed(from, to);
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

    public static class OrderNotFoundException extends RuntimeException { }
}
