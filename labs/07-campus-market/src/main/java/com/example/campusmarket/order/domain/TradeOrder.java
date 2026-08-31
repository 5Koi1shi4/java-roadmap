package com.example.campusmarket.order.domain;

import com.example.campusmarket.catalog.domain.WarrantyTerm;
import com.example.campusmarket.shared.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 订单及其商品声明的不可变下单快照。 */
public final class TradeOrder {
    private static final long PAYMENT_DEADLINE_SECONDS = 15 * 60L;

    private final UUID id;
    private final UUID buyerId;
    private final UUID sellerId;
    private final ListingSnapshot snapshot;
    private final int quantity;
    private final Money totalAmount;
    private final Instant createdAt;
    private final Instant paymentDeadline;
    private final OrderStatus status;

    private TradeOrder(UUID id, UUID buyerId, UUID sellerId, ListingSnapshot snapshot,
                       int quantity, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "订单ID不能为空");
        this.buyerId = Objects.requireNonNull(buyerId, "买家不能为空");
        this.sellerId = Objects.requireNonNull(sellerId, "卖家不能为空");
        this.snapshot = Objects.requireNonNull(snapshot, "商品快照不能为空");
        if (!sellerId.equals(snapshot.sellerId())) {
            throw new IllegalArgumentException("订单卖家与商品快照卖家不一致");
        }
        if (buyerId.equals(sellerId)) {
            throw new IllegalArgumentException("不能购买自己的商品");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("购买数量必须为正数");
        }
        this.quantity = quantity;
        this.totalAmount = snapshot.unitPrice().multiply(quantity);
        this.createdAt = Objects.requireNonNull(createdAt, "创建时间不能为空");
        this.paymentDeadline = createdAt.plusSeconds(PAYMENT_DEADLINE_SECONDS);
        this.status = OrderStatus.PENDING_PAYMENT;
    }

    public static TradeOrder create(UUID id, UUID buyerId, UUID sellerId, ListingSnapshot snapshot,
                                    int quantity, Instant createdAt) {
        return new TradeOrder(id, buyerId, sellerId, snapshot, quantity, createdAt);
    }

    public UUID id() { return id; }
    public UUID buyerId() { return buyerId; }
    public UUID sellerId() { return sellerId; }
    public ListingSnapshot snapshot() { return snapshot; }
    public int quantity() { return quantity; }
    public Money totalAmount() { return totalAmount; }
    public Instant createdAt() { return createdAt; }
    public Instant paymentDeadline() { return paymentDeadline; }
    public OrderStatus status() { return status; }

    public record ListingSnapshot(UUID listingId, UUID sellerId, String title, String description,
                                  String category, Money unitPrice, WarrantyTerm warrantyTerm,
                                  String warrantyScope, String manufacturerWarrantyProof,
                                  Instant manufacturerWarrantyExpiresAt) {
        public ListingSnapshot {
            Objects.requireNonNull(listingId, "商品ID不能为空");
            Objects.requireNonNull(sellerId, "卖家不能为空");
            requireText(title, "商品标题");
            requireText(description, "商品描述");
            requireText(category, "商品分类");
            Objects.requireNonNull(unitPrice, "商品单价不能为空");
            Objects.requireNonNull(warrantyTerm, "卖家质保声明不能为空");
            if (warrantyScope == null || warrantyScope.isBlank()) {
                throw new IllegalArgumentException("质保范围不能为空");
            }
            if ((manufacturerWarrantyProof == null) != (manufacturerWarrantyExpiresAt == null)) {
                throw new IllegalArgumentException("厂家质保凭证与到期日必须同时存在或为空");
            }
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + "不能为空");
            }
        }
    }
}
