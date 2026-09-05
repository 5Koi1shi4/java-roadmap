package com.example.campusmarket.shared;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 稳定的七字段聚合事件契约。 */
public record DomainEvent(
    UUID eventId,
    String eventType,
    String aggregateId,
    long aggregateVersion,
    Instant occurredAt,
    int schemaVersion,
    Map<String, Object> payload
) {
    private static final Set<String> SUPPORTED_EVENT_TYPES = Set.of(
        "LISTING_CREATED", "LISTING_UPDATED", "LISTING_PUBLISHED", "LISTING_OFF_SALE", "LISTING_SOLD_OUT",
        "INVENTORY_CHANGED", "ORDER_CREATED", "ORDER_CANCELLED", "ORDER_PAID", "ORDER_HANDOFF_CONFIRMED",
        "ORDER_RECEIPT_CONFIRMED", "ORDER_DISPUTED", "ORDER_REFUNDING_CANCEL", "ORDER_REFUNDED",
        "ORDER_SETTLED", "ORDER_TRIAL_ELAPSED", "PAYMENT_CREATED", "PAYMENT_SUCCEEDED", "PAYMENT_FAILED", "PAYMENT_CALLBACK_RECEIVED",
        "REFUND_REQUESTED", "REFUND_SUCCEEDED", "REFUND_FAILED", "DISPUTE_CREATED", "DISPUTE_RESOLVED", "DISPUTE_SLA_ALERT",
        "WARRANTY_CASE_CREATED", "WARRANTY_RESOLVED", "SELLER_OBLIGATION_CREATED", "SELLER_OBLIGATION_FUNDED",
        "SETTLEMENT_CREATED", "REVIEW_CREATED");

    public DomainEvent {
        Objects.requireNonNull(eventId, "eventId 不能为空");
        requireNonBlank(eventType, "eventType 不能为空");
        if (!SUPPORTED_EVENT_TYPES.contains(eventType)) {
            throw new IllegalArgumentException("不支持的事件类型: " + eventType);
        }
        requireNonBlank(aggregateId, "aggregateId 不能为空");
        if (aggregateVersion <= 0) {
            throw new IllegalArgumentException("aggregateVersion 必须为正数");
        }
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("仅支持 schemaVersion=1");
        }
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload 不能为空"));
    }

    private static void requireNonBlank(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
