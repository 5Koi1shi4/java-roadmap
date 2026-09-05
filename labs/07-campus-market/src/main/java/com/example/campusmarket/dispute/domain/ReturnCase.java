package com.example.campusmarket.dispute.domain;

import java.time.Instant;
import java.util.Objects;

/** 退回证明和数量的无框架领域模型；外部支付和库存操作由应用层编排。 */
public final class ReturnCase {
    private final int purchasedQuantity;
    private final int approvedQuantity;
    private final long unitPriceFen;
    private final Instant openedAt;
    private final Instant hardDeadline;
    private ReturnProofType proofType;
    private String proofReference;

    private ReturnCase(int purchasedQuantity, int approvedQuantity, long unitPriceFen,
                       Instant openedAt, Instant hardDeadline) {
        if (purchasedQuantity <= 0 || approvedQuantity <= 0 || approvedQuantity > purchasedQuantity)
            throw new IllegalArgumentException("退回数量无效");
        if (unitPriceFen <= 0) throw new IllegalArgumentException("单价必须为正数");
        this.purchasedQuantity = purchasedQuantity;
        this.approvedQuantity = approvedQuantity;
        this.unitPriceFen = unitPriceFen;
        this.openedAt = Objects.requireNonNull(openedAt, "创建时间不能为空");
        this.hardDeadline = Objects.requireNonNull(hardDeadline, "硬期限不能为空");
        if (hardDeadline.isBefore(openedAt)) throw new IllegalArgumentException("硬期限不能早于创建时间");
    }

    public static ReturnCase open(int purchasedQuantity, int approvedQuantity, long unitPriceFen,
                                  Instant openedAt, Instant hardDeadline) {
        return new ReturnCase(purchasedQuantity, approvedQuantity, unitPriceFen, openedAt, hardDeadline);
    }

    /** 记录证明不改变退款资格；买家证据是材料，不是可信履约事实。 */
    public void recordProof(ReturnProofType type, String reference) {
        this.proofType = Objects.requireNonNull(type, "证明类型不能为空");
        if (reference == null || reference.isBlank() || reference.length() > 500)
            throw new IllegalArgumentException("证明引用无效");
        this.proofReference = reference;
    }

    public boolean mayAutoRefundAt(Instant now) {
        Objects.requireNonNull(now, "当前时间不能为空");
        return !now.isBefore(hardDeadline) && proofType != null && proofType.isTrusted();
    }

    public long refundAmountFen() {
        try { return Math.multiplyExact(unitPriceFen, approvedQuantity); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("退款金额溢出", overflow); }
    }

    public int purchasedQuantity() { return purchasedQuantity; }
    public int approvedQuantity() { return approvedQuantity; }
    public long unitPriceFen() { return unitPriceFen; }
    public Instant openedAt() { return openedAt; }
    public Instant hardDeadline() { return hardDeadline; }
    public ReturnProofType proofType() { return proofType; }
    public String proofReference() { return proofReference; }
}
