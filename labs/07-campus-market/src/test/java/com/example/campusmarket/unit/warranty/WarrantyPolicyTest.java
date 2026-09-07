package com.example.campusmarket.unit.warranty;

import com.example.campusmarket.catalog.domain.WarrantyTerm;
import com.example.campusmarket.order.domain.TradeOrder;
import com.example.campusmarket.order.domain.OrderStatus;
import com.example.campusmarket.shared.Money;
import com.example.campusmarket.warranty.domain.WarrantyCase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WarrantyPolicyTest {
    private final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void standardWarrantyUsesLeftClosedRightOpenDeadline() {
        TradeOrder settled = settledOrderWithSellerWarranty(90, t0);
        assertThat(WarrantyCase.open(settled, 1, WarrantyCase.Reason.FUNCTIONAL_DEFECT,
            t0.plusSeconds(89 * 86_400L))).isNotNull();
        assertThatThrownBy(() -> WarrantyCase.open(settled, 1, WarrantyCase.Reason.FUNCTIONAL_DEFECT,
            t0.plusSeconds(90 * 86_400L))).isInstanceOf(IllegalStateException.class);
        assertThat(settled.status()).isEqualTo(OrderStatus.SETTLED);
    }

    @Test
    void platformOnlyWarrantyRejectsDayEightClaim() {
        TradeOrder settled = settledOrderWithoutSellerWarranty(t0);
        assertThatThrownBy(() -> WarrantyCase.open(settled, 1, WarrantyCase.Reason.FUNCTIONAL_DEFECT,
            t0.plusSeconds(8 * 86_400L))).isInstanceOf(IllegalStateException.class);
    }

    private TradeOrder settledOrderWithSellerWarranty(int days, Instant createdAt) {
        UUID seller = UUID.randomUUID();
        return TradeOrder.reconstitute(UUID.randomUUID(), UUID.randomUUID(), seller,
            new TradeOrder.ListingSnapshot(UUID.randomUUID(), seller, "键盘", "描述", "数码",
                Money.ofFen(1200), WarrantyTerm.sellerWarrantyDays(days), "SELLER_NON_HUMAN_FUNCTIONAL_FAILURE", null, null),
            1, createdAt, OrderStatus.SETTLED);
    }

    private TradeOrder settledOrderWithoutSellerWarranty(Instant createdAt) {
        UUID seller = UUID.randomUUID();
        return TradeOrder.reconstitute(UUID.randomUUID(), UUID.randomUUID(), seller,
            new TradeOrder.ListingSnapshot(UUID.randomUUID(), seller, "键盘", "描述", "数码",
                Money.ofFen(1200), WarrantyTerm.none(), "SELLER_NON_HUMAN_FUNCTIONAL_FAILURE", null, null),
            1, createdAt, OrderStatus.SETTLED);
    }
}
