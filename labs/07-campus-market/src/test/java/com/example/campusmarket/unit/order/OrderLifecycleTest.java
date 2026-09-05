package com.example.campusmarket.unit.order;

import com.example.campusmarket.order.application.OrderLifecycleService;
import com.example.campusmarket.order.domain.OrderStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 订单生命周期的纯领域规则测试；不启动 Spring 或外部依赖。 */
class OrderLifecycleTest {
    @Test
    void allowsExactlyTheSpecificationTransitionMatrix() {
        Map<OrderStatus, OrderStatus[]> allowed = Map.of(
            OrderStatus.PENDING_PAYMENT, new OrderStatus[]{OrderStatus.AWAITING_HANDOFF, OrderStatus.CANCELLED},
            OrderStatus.AWAITING_HANDOFF, new OrderStatus[]{OrderStatus.AWAITING_RECEIPT, OrderStatus.REFUNDING_CANCEL},
            OrderStatus.REFUNDING_CANCEL, new OrderStatus[]{OrderStatus.REFUNDED},
            OrderStatus.AWAITING_RECEIPT, new OrderStatus[]{OrderStatus.AFTERSALE_WINDOW, OrderStatus.DISPUTED},
            OrderStatus.AFTERSALE_WINDOW, new OrderStatus[]{OrderStatus.DISPUTED},
            OrderStatus.DISPUTED, new OrderStatus[]{OrderStatus.AFTERSALE_WINDOW, OrderStatus.REFUNDED}
        );
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                boolean expected = false;
                for (OrderStatus candidate : allowed.getOrDefault(from, new OrderStatus[0])) {
                    expected |= candidate == to;
                }
                assertThat(OrderLifecycleService.isAllowedTransition(from, to))
                    .as("%s -> %s", from, to).isEqualTo(expected);
            }
        }
    }

    @Test
    void terminalStatesCannotMoveAndReceiptCannotBeConfirmedBeforeHandoff() {
        assertThat(OrderLifecycleService.isAllowedTransition(OrderStatus.SETTLED, OrderStatus.DISPUTED)).isFalse();
        assertThat(OrderLifecycleService.isAllowedTransition(OrderStatus.CANCELLED, OrderStatus.REFUNDED)).isFalse();
        assertThat(OrderLifecycleService.isAllowedTransition(OrderStatus.REFUNDED, OrderStatus.SETTLED)).isFalse();
        assertThat(OrderLifecycleService.isReceiptConfirmationAllowed(OrderStatus.AWAITING_HANDOFF)).isFalse();
        assertThat(OrderLifecycleService.isReceiptConfirmationAllowed(OrderStatus.AWAITING_RECEIPT)).isTrue();
    }

    @Test
    void acceptanceReasonWindowUsesDatabaseNowAndLeftClosedRightOpenBoundary() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        assertThat(OrderLifecycleService.isReasonAllowed("QUANTITY", t0, t0.minusNanos(1))).isFalse();
        assertThat(OrderLifecycleService.isReasonAllowed("QUANTITY", t0, t0)).isTrue();
        assertThat(OrderLifecycleService.isReasonAllowed("QUANTITY", t0, t0.plus(72, ChronoUnit.HOURS).minusNanos(1))).isTrue();
        assertThat(OrderLifecycleService.isReasonAllowed("QUANTITY", t0, t0.plus(72, ChronoUnit.HOURS))).isFalse();
        assertThat(OrderLifecycleService.isReasonAllowed("FUNCTIONAL_DEFECT", t0, t0.plus(72, ChronoUnit.HOURS))).isTrue();
        assertThat(OrderLifecycleService.isReasonAllowed("FUNCTIONAL_DEFECT", t0, t0.plus(7, ChronoUnit.DAYS).minusNanos(1))).isTrue();
        assertThat(OrderLifecycleService.isReasonAllowed("FUNCTIONAL_DEFECT", t0, t0.plus(7, ChronoUnit.DAYS))).isFalse();
        assertThat(OrderLifecycleService.isReasonAllowed("", t0, t0)).isFalse();
        assertThat(OrderLifecycleService.isReasonAllowed("UNKNOWN", t0, t0)).isFalse();
    }

    @Test
    void reasonApiRequiresExplicitT0AndDatabaseNow() {
        assertThatThrownBy(() -> OrderLifecycleService.class
            .getDeclaredMethod("isReasonAllowed", String.class, Instant.class))
            .isInstanceOf(NoSuchMethodException.class);
    }
}
