package com.example.campusmarket.warranty.domain;

import com.example.campusmarket.catalog.domain.WarrantyTerm;
import com.example.campusmarket.dispute.domain.DisputeReason;
import com.example.campusmarket.order.domain.OrderStatus;
import com.example.campusmarket.order.domain.TradeOrder;
import com.example.campusmarket.shared.Money;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 结算后仍可独立存在的卖家质保案件；不改变订单或结算状态。 */
public final class WarrantyCase {
    public enum Status { OPEN, SELLER_RESPONDED, UNDER_REVIEW, ESCALATED, RESOLVED, REJECTED }
    public enum Reason {
        FUNCTIONAL_DEFECT(false), INVALID_MANUFACTURER_WARRANTY_PROOF(false),
        WATER_DAMAGE(true), DROP_DAMAGE(true), WRONG_POWER(true), UNAUTHORIZED_REPAIR(true),
        NORMAL_WEAR(true), DISCLOSED_ISSUE(true);
        private final boolean exclusion;
        Reason(boolean exclusion) { this.exclusion = exclusion; }
        public boolean isExclusion() { return exclusion; }
        public static Reason from(DisputeReason reason) { return reason == null ? null : valueOf(reason.name()); }
    }

    private final UUID id;
    private final UUID orderId;
    private final UUID buyerId;
    private final UUID sellerId;
    private final int warrantyDays;
    private final String warrantyScopeSnapshot;
    private final String manufacturerWarrantyProofSnapshot;
    private final Instant manufacturerWarrantyExpiresAt;
    private final int disputedQuantity;
    private final Reason reason;
    private final Instant openedAt;
    private final Instant sellerDeadline;
    private Status status;
    private WarrantyDecision decision;
    private long compensationAmountFen;

    private WarrantyCase(UUID id, TradeOrder order, int quantity, Reason reason, Instant openedAt, int warrantyDays) {
        this.id = Objects.requireNonNull(id, "质保案件ID不能为空");
        this.orderId = order.id(); this.buyerId = order.buyerId(); this.sellerId = order.sellerId();
        this.warrantyDays = warrantyDays;
        this.warrantyScopeSnapshot = order.snapshot().warrantyScope();
        this.manufacturerWarrantyProofSnapshot = order.snapshot().manufacturerWarrantyProof();
        this.manufacturerWarrantyExpiresAt = order.snapshot().manufacturerWarrantyExpiresAt();
        if (quantity <= 0 || quantity > order.quantity()) throw new IllegalArgumentException("质保数量无效");
        this.disputedQuantity = quantity;
        this.reason = Objects.requireNonNull(reason, "质保理由不能为空");
        this.openedAt = Objects.requireNonNull(openedAt, "创建时间不能为空");
        this.sellerDeadline = openedAt.plus(Duration.ofHours(72));
        this.status = Status.OPEN;
    }

    public static WarrantyCase open(TradeOrder order, int quantity, Reason reason, Instant now) {
        Objects.requireNonNull(order, "订单不能为空"); Objects.requireNonNull(now, "数据库时间不能为空");
        if (order.status() != OrderStatus.SETTLED) throw new IllegalStateException("只有已结算订单可申请延长质保");
        Integer declared = order.snapshot().warrantyTerm().sellerWarrantyDays();
        int days = declared == null ? 7 : declared;
        Instant start = order.createdAt();
        if (now.isBefore(start) || !now.isBefore(start.plus(Duration.ofDays(days))))
            throw new IllegalStateException("已超过质保期限");
        if (reason == Reason.INVALID_MANUFACTURER_WARRANTY_PROOF
            && order.snapshot().manufacturerWarrantyProof() == null) throw new IllegalStateException("订单快照未承诺厂家质保凭证");
        return new WarrantyCase(UUID.randomUUID(), order, quantity, reason, now, days);
    }

    public static WarrantyCase open(TradeOrder order, int quantity, DisputeReason reason, Instant now) {
        return open(order, quantity, Reason.from(reason), now);
    }

    public static Money approvedCompensation(Money verifiedQuote, String evidenceId,
                                             Money paidAmount, Money successfulRefund, Money reservedRefund) {
        Objects.requireNonNull(verifiedQuote, "维修报价不能为空"); Objects.requireNonNull(evidenceId, "报价证据不能为空");
        if (evidenceId.isBlank() || verifiedQuote.fen() <= 0) throw new IllegalArgumentException("维修报价必须为正数且有证据");
        Objects.requireNonNull(paidAmount, "实付金额不能为空"); Objects.requireNonNull(successfulRefund, "成功退款不能为空");
        Objects.requireNonNull(reservedRefund, "预占退款不能为空");
        long remaining;
        try { remaining = Math.subtractExact(Math.subtractExact(paidAmount.fen(), successfulRefund.fen()), reservedRefund.fen()); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("金额计算溢出", ex); }
        if (remaining < 0) throw new IllegalStateException("退款额度已超出实付金额");
        return Money.ofFen(Math.min(verifiedQuote.fen(), remaining));
    }
    public static long approvedCompensationFen(long verifiedQuoteFen, long paidAmountFen,
                                               long successfulRefundFen, long reservedRefundFen, String evidenceId) {
        return approvedCompensation(Money.ofFen(verifiedQuoteFen), evidenceId, Money.ofFen(paidAmountFen),
            Money.ofFen(successfulRefundFen), Money.ofFen(reservedRefundFen)).fen();
    }

    public WarrantyCase respond() { if (status != Status.OPEN) throw new IllegalStateException("质保状态不允许卖家回应"); status = Status.SELLER_RESPONDED; return this; }
    public WarrantyCase decide(WarrantyDecision choice, long compensationFen) {
        Objects.requireNonNull(choice, "质保裁定不能为空");
        if (status == Status.RESOLVED || status == Status.REJECTED) throw new IllegalStateException("质保案件已经裁定");
        if (choice == WarrantyDecision.REPAIR_COMPENSATION && compensationFen <= 0) throw new IllegalArgumentException("维修补偿必须为正数");
        if (choice != WarrantyDecision.REPAIR_COMPENSATION && compensationFen != 0) throw new IllegalArgumentException("非维修裁定不得有补偿金额");
        if (reason.isExclusion() && choice != WarrantyDecision.REJECT) throw new IllegalStateException("排除原因不得判定卖家承担质保");
        this.decision = choice; this.compensationAmountFen = compensationFen;
        this.status = choice == WarrantyDecision.REJECT ? Status.REJECTED : Status.RESOLVED;
        return this;
    }

    public UUID id() { return id; } public UUID orderId() { return orderId; } public UUID buyerId() { return buyerId; }
    public UUID sellerId() { return sellerId; } public int warrantyDays() { return warrantyDays; }
    public String warrantyScopeSnapshot() { return warrantyScopeSnapshot; }
    public String manufacturerWarrantyProofSnapshot() { return manufacturerWarrantyProofSnapshot; }
    public Instant manufacturerWarrantyExpiresAt() { return manufacturerWarrantyExpiresAt; }
    public int disputedQuantity() { return disputedQuantity; } public Reason reason() { return reason; }
    public Instant openedAt() { return openedAt; } public Instant sellerDeadline() { return sellerDeadline; }
    public Status status() { return status; } public WarrantyDecision decision() { return decision; }
    public long compensationAmountFen() { return compensationAmountFen; }
}
