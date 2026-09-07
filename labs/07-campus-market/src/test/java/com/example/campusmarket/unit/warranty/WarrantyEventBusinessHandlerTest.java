package com.example.campusmarket.unit.warranty;

import com.example.campusmarket.shared.DomainEvent;
import com.example.campusmarket.warranty.application.WarrantyEventBusinessHandler;
import com.example.campusmarket.payment.application.RefundService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WarrantyEventBusinessHandlerTest {
    @Test
    void refundEventUsesStableObligationIdempotencyKey() {
        RefundService refunds = mock(RefundService.class);
        WarrantyEventBusinessHandler handler = new WarrantyEventBusinessHandler(refunds);
        UUID order = UUID.randomUUID(), caseId = UUID.randomUUID(), obligation = UUID.randomUUID();
        handler.handle(new DomainEvent(UUID.randomUUID(), "WARRANTY_REFUND_REQUESTED", obligation.toString(), 1,
            Instant.now(), 1, Map.of("orderId", order.toString(), "caseId", caseId.toString(),
            "obligationId", obligation.toString(), "amountFen", 800)));
        verify(refunds).requestRefund(eq(order), eq("warranty-refund-" + obligation), eq(com.example.campusmarket.shared.Money.ofFen(800)), eq("WARRANTY"), eq(caseId));
    }

    @Test
    void nonWarrantyEventIsRejectedSoReliableConsumerCannotAckItAsCompleted() {
        WarrantyEventBusinessHandler handler = new WarrantyEventBusinessHandler(mock(RefundService.class));
        DomainEvent event = new DomainEvent(UUID.randomUUID(), "ORDER_CREATED", "order", 1,
            Instant.now(), 1, Map.of("orderId", "order"));
        assertThatThrownBy(() -> handler.handle(event))
            .isInstanceOf(WarrantyEventBusinessHandler.UnsupportedEventException.class);
    }
}
