package com.example.campusmarket.unit.order;

import com.example.campusmarket.catalog.domain.WarrantyTerm;
import com.example.campusmarket.order.domain.OrderStatus;
import com.example.campusmarket.order.domain.TradeOrder;
import com.example.campusmarket.shared.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradeOrderTest {
    private static final UUID LISTING_ID = UUID.randomUUID();
    private static final UUID SELLER_ID = UUID.randomUUID();
    private static final UUID BUYER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

    @Test
    void rejectsSellerBuyingOwnListing() {
        TradeOrder.ListingSnapshot snapshot = snapshot(SELLER_ID);

        assertThatThrownBy(() -> TradeOrder.create(UUID.randomUUID(), SELLER_ID, SELLER_ID, snapshot, 2, NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不能购买自己的商品");
    }

    @Test
    void rejectsSnapshotSellerMismatch() {
        TradeOrder.ListingSnapshot snapshot = snapshot(UUID.randomUUID());

        assertThatThrownBy(() -> TradeOrder.create(UUID.randomUUID(), BUYER_ID, SELLER_ID, snapshot, 1, NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("卖家");
    }

    @Test
    void createsPendingPaymentOrderWithImmutableWarrantySnapshot() {
        TradeOrder.ListingSnapshot snapshot = snapshot(SELLER_ID);

        TradeOrder order = TradeOrder.create(UUID.randomUUID(), BUYER_ID, SELLER_ID, snapshot, 2, NOW);

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.quantity()).isEqualTo(2);
        assertThat(order.totalAmount()).isEqualTo(Money.ofFen(2_000));
        assertThat(order.paymentDeadline()).isEqualTo(NOW.plusSeconds(15 * 60));
        assertThat(order.snapshot().title()).isEqualTo("高等数学");
        assertThat(order.snapshot().description()).isEqualTo("九成新");
        assertThat(order.snapshot().warrantyTerm()).isEqualTo(WarrantyTerm.sellerWarrantyDays(90));
        assertThat(order.snapshot().manufacturerWarrantyProof()).isEqualTo("proof-sha256");
        assertThat(order.snapshot().manufacturerWarrantyExpiresAt()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    }

    @Test
    void rejectsInvalidQuantityAndSnapshot() {
        assertThatThrownBy(() -> TradeOrder.create(UUID.randomUUID(), BUYER_ID, SELLER_ID, snapshot(SELLER_ID), 0, NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TradeOrder.create(UUID.randomUUID(), BUYER_ID, SELLER_ID, null, 1, NOW))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsAmountOverflow() {
        TradeOrder.ListingSnapshot expensive = new TradeOrder.ListingSnapshot(
            LISTING_ID, SELLER_ID, "高价商品", "描述", "教材", Money.ofFen(Long.MAX_VALUE),
            WarrantyTerm.none(), "SELLER_NON_HUMAN_FUNCTIONAL_FAILURE", null, null);

        assertThatThrownBy(() -> TradeOrder.create(UUID.randomUUID(), BUYER_ID, SELLER_ID, expensive, 2, NOW))
            .isInstanceOf(ArithmeticException.class);
    }

    private TradeOrder.ListingSnapshot snapshot(UUID sellerId) {
        return new TradeOrder.ListingSnapshot(
            LISTING_ID, sellerId, "高等数学", "九成新", "教材", Money.ofFen(1_000),
            WarrantyTerm.sellerWarrantyDays(90), "SELLER_NON_HUMAN_FUNCTIONAL_FAILURE",
            "proof-sha256", Instant.parse("2027-01-01T00:00:00Z"));
    }
}
