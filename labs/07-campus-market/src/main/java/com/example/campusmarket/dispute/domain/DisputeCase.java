package com.example.campusmarket.dispute.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 普通争议的一次性领域模型；金额由退款应用用例按订单单价计算。 */
public final class DisputeCase {
    public enum Status { OPEN, SELLER_RESPONDED, UNDER_REVIEW, ESCALATED, RESOLVED, REJECTED }

    private final UUID id;
    private final UUID orderId;
    private final UUID buyerId;
    private final UUID sellerId;
    private final int purchasedQuantity;
    private final int disputedQuantity;
    private final DisputeReason reason;
    private final Instant openedAt;
    private Status status;
    private Decision decision;

    private DisputeCase(UUID id, UUID orderId, UUID buyerId, UUID sellerId, int purchasedQuantity,
                        int disputedQuantity, DisputeReason reason, Instant openedAt) {
        this.id = Objects.requireNonNull(id, "争议ID不能为空");
        this.orderId = Objects.requireNonNull(orderId, "订单ID不能为空");
        this.buyerId = Objects.requireNonNull(buyerId, "买家不能为空");
        this.sellerId = Objects.requireNonNull(sellerId, "卖家不能为空");
        if (purchasedQuantity <= 0 || disputedQuantity <= 0 || disputedQuantity > purchasedQuantity)
            throw new IllegalArgumentException("争议数量无效");
        this.purchasedQuantity = purchasedQuantity;
        this.disputedQuantity = disputedQuantity;
        this.reason = Objects.requireNonNull(reason, "争议理由不能为空");
        this.openedAt = Objects.requireNonNull(openedAt, "创建时间不能为空");
        this.status = Status.OPEN;
    }

    public static DisputeCase open(UUID id, UUID orderId, UUID buyerId, UUID sellerId, int purchasedQuantity,
                                   int disputedQuantity, DisputeReason reason, Instant t0, Instant now) {
        Objects.requireNonNull(now, "数据库时间不能为空");
        if (reason == null || (!reason.isExclusion() && !reason.allowedAt(t0, now)))
            throw new IllegalStateException("当前售后阶段不允许该争议理由");
        return new DisputeCase(id, orderId, buyerId, sellerId, purchasedQuantity, disputedQuantity, reason, now);
    }

    /** 收货尚未确认时仍允许验收争议，T0 由后续订单收货流程产生。 */
    public static DisputeCase openBeforeReceipt(UUID id, UUID orderId, UUID buyerId, UUID sellerId,
                                                int purchasedQuantity, int disputedQuantity,
                                                DisputeReason reason, Instant now) {
        if (reason == null || reason.isExclusion()) throw new IllegalStateException("当前售后阶段不允许该争议理由");
        return new DisputeCase(id, orderId, buyerId, sellerId, purchasedQuantity, disputedQuantity, reason, now);
    }

    public Decision decide(DisputeDecision choice, int approvedQuantity) {
        if (status == Status.RESOLVED || status == Status.REJECTED) throw new IllegalStateException("争议已经裁决");
        Objects.requireNonNull(choice, "裁决不能为空");
        if (choice == DisputeDecision.REJECT) {
            if (approvedQuantity != 0) throw new IllegalArgumentException("驳回裁决不得批准数量");
        } else if (approvedQuantity <= 0 || approvedQuantity > disputedQuantity) {
            throw new IllegalArgumentException("批准数量超出争议数量");
        }
        if (reason.isExclusion() && choice != DisputeDecision.REJECT)
            throw new IllegalStateException("固定排除原因不得直接判定卖家责任");
        this.decision = new Decision(choice, approvedQuantity);
        this.status = choice == DisputeDecision.REJECT ? Status.REJECTED : Status.RESOLVED;
        return decision;
    }

    public Decision decideRefundOnly(int approvedQuantity) {
        return decide(DisputeDecision.REFUND_ONLY, approvedQuantity);
    }

    public Decision decideReturnAndRefund(int approvedQuantity) {
        return decide(DisputeDecision.RETURN_AND_REFUND, approvedQuantity);
    }

    public void sellerResponded() {
        if (status != Status.OPEN) throw new IllegalStateException("争议状态不允许卖家回应");
        status = Status.SELLER_RESPONDED;
    }

    public static void ensureAvailableQuantity(int purchasedQuantity, List<DisputeCase> previous, int requested) {
        if (purchasedQuantity <= 0 || requested <= 0) throw new IllegalArgumentException("争议数量无效");
        long used = previous == null ? 0 : previous.stream().filter(Objects::nonNull)
            .mapToLong(c -> c.decision == null ? c.disputedQuantity : c.decision.approvedQuantity()).sum();
        if (used + requested > purchasedQuantity) throw new IllegalArgumentException("累计争议数量超过购买数量");
    }

    public static boolean isReasonAllowed(DisputeReason reason, Instant t0, Instant now) {
        return reason != null && reason.allowedAt(t0, now);
    }

    public UUID id() { return id; }
    public UUID orderId() { return orderId; }
    public UUID buyerId() { return buyerId; }
    public UUID sellerId() { return sellerId; }
    public int purchasedQuantity() { return purchasedQuantity; }
    public int disputedQuantity() { return disputedQuantity; }
    public DisputeReason reason() { return reason; }
    public Instant openedAt() { return openedAt; }
    public Status status() { return status; }
    public Decision decision() { return decision; }
    public boolean isResolved() { return status == Status.RESOLVED || status == Status.REJECTED; }

    public record Decision(DisputeDecision decision, int approvedQuantity) {
        public Decision {
            Objects.requireNonNull(decision, "裁决不能为空");
            if (approvedQuantity < 0) throw new IllegalArgumentException("批准数量不能为负");
        }
    }
}
