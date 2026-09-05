package com.example.campusmarket.payment.application;

import com.example.campusmarket.shared.Money;
import org.springframework.http.HttpHeaders;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 支付服务与外部支付提供方之间的稳定端口；真实商户适配器可在未来替换实现。 */
public interface PaymentGateway {
    PaymentCreated createPayment(CreatePaymentRequest request);
    PaymentStatus queryPayment(String providerReference);
    /** 按原幂等键查询未知支付，避免对未知结果盲目重新创建。 */
    default PaymentStatus queryPaymentByIdempotencyKey(String idempotencyKey) {
        throw new UnsupportedOperationException("提供方不支持按幂等键对账");
    }
    RefundCreated requestRefund(CreateRefundRequest request);
    RefundStatus queryRefund(String providerReference);
    /** 按退款幂等键查询未知退款，禁止超时后重复创建。 */
    default RefundStatus queryRefundByIdempotencyKey(String idempotencyKey) {
        throw new UnsupportedOperationException("提供方不支持按幂等键查询退款");
    }
    VerifiedCallback verifyAndParse(byte[] rawBody, HttpHeaders headers);

    record CreatePaymentRequest(UUID orderId, Money amount, String idempotencyKey) {
        public CreatePaymentRequest {
            Objects.requireNonNull(orderId, "订单ID不能为空");
            Objects.requireNonNull(amount, "支付金额不能为空");
            requireKey(idempotencyKey, "支付幂等键");
        }
        public CreatePaymentRequest(String orderId, long amountFen, String idempotencyKey) {
            this(UUID.fromString(orderId), Money.ofFen(amountFen), idempotencyKey);
        }
    }

    record PaymentCreated(String providerReference, PaymentStatus status) {
        public PaymentCreated {
            requireReference(providerReference);
            Objects.requireNonNull(status, "支付状态不能为空");
        }
    }

    record PaymentStatus(String providerReference, Status status, long amountFen) {
        public PaymentStatus {
            requireReference(providerReference);
            Objects.requireNonNull(status, "支付状态不能为空");
            if (amountFen < 0) throw new IllegalArgumentException("支付金额不能为负数");
        }
        public enum Status { PENDING, SUCCEEDED, FAILED, UNKNOWN }
        public boolean succeeded() { return status == Status.SUCCEEDED; }
    }

    record CreateRefundRequest(UUID orderId, String paymentProviderReference, Money amount, String idempotencyKey) {
        public CreateRefundRequest {
            Objects.requireNonNull(orderId, "订单ID不能为空");
            requireReference(paymentProviderReference);
            Objects.requireNonNull(amount, "退款金额不能为空");
            requireKey(idempotencyKey, "退款幂等键");
        }
        public CreateRefundRequest(String orderId, String paymentProviderReference, long amountFen, String idempotencyKey) {
            this(UUID.fromString(orderId), paymentProviderReference, Money.ofFen(amountFen), idempotencyKey);
        }
    }

    record RefundCreated(String providerReference, RefundStatus.Status status, long amountFen) {
        public RefundCreated {
            requireReference(providerReference);
            Objects.requireNonNull(status, "退款状态不能为空");
            if (amountFen <= 0) throw new IllegalArgumentException("退款金额必须为正数");
        }
    }

    record RefundStatus(String providerReference, Status status, long amountFen) {
        public RefundStatus {
            requireReference(providerReference);
            Objects.requireNonNull(status, "退款状态不能为空");
            if (amountFen < 0) throw new IllegalArgumentException("退款金额不能为负数");
        }
        public enum Status { PENDING, SUCCEEDED, FAILED, UNKNOWN }
        public boolean succeeded() { return status == Status.SUCCEEDED; }
    }

    record VerifiedCallback(String provider, String providerEventId, CallbackType type,
                            String providerReference, long amountFen, String status,
                            Instant occurredAt, String nonce) {
        public VerifiedCallback {
            requireKey(provider, "支付提供方");
            requireReference(providerEventId);
            Objects.requireNonNull(type, "回调类型不能为空");
            requireReference(providerReference);
            if (amountFen < 0) throw new IllegalArgumentException("回调金额不能为负数");
            requireKey(status, "回调状态");
            Objects.requireNonNull(occurredAt, "回调时间不能为空");
            requireReference(nonce);
        }
        public enum CallbackType { PAYMENT, REFUND }
    }

    private static void requireReference(String value) {
        requireKey(value, "provider reference");
    }
    private static void requireKey(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 191) {
            throw new IllegalArgumentException(name + "无效");
        }
    }
}
