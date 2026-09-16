package com.example.campusmarket.payment.domain;

import com.example.campusmarket.shared.Money;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** 支付金额与退款额度的领域约束；持久化层以条件更新实现同一约束。 */
public final class PaymentAggregate {
    private final long paidAmountFen;
    private long successfulRefundFen;
    private long reservedRefundFen;
    private final Map<String, Long> reservations = new HashMap<>();

    private PaymentAggregate(Money paidAmount) {
        this.paidAmountFen = Objects.requireNonNull(paidAmount, "实付金额不能为空").fen();
    }

    public static PaymentAggregate paid(Money paidAmount) {
        return new PaymentAggregate(paidAmount);
    }

    /** 预占一个业务退款键；重复相同键视为幂等成功，不重复占额。 */
    public synchronized boolean reserveRefund(String idempotencyKey, Money amount) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("退款幂等键不能为空");
        }
        long fen = Objects.requireNonNull(amount, "退款金额不能为空").fen();
        Long existing = reservations.get(idempotencyKey);
        if (existing != null) return existing == fen;
        if (fen == 0 || successfulRefundFen > paidAmountFen - fen
                || reservedRefundFen > paidAmountFen - successfulRefundFen - fen) {
            return false;
        }
        reservations.put(idempotencyKey, fen);
        reservedRefundFen += fen;
        return true;
    }

    public synchronized void completeRefund(String idempotencyKey) {
        Long amount = reservations.remove(Objects.requireNonNull(idempotencyKey, "退款幂等键不能为空"));
        if (amount == null) return;
        reservedRefundFen -= amount;
        successfulRefundFen += amount;
    }

    public synchronized void releaseRefund(String idempotencyKey) {
        Long amount = reservations.remove(Objects.requireNonNull(idempotencyKey, "退款幂等键不能为空"));
        if (amount != null) reservedRefundFen -= amount;
    }

    public Money paidAmount() { return Money.ofFen(paidAmountFen); }
    public synchronized Money successfulRefund() { return Money.ofFen(successfulRefundFen); }
    public synchronized Money reservedRefund() { return Money.ofFen(reservedRefundFen); }
}
