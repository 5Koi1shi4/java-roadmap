package com.example.campusmarket.warranty.application;

import com.example.campusmarket.messaging.EventBusinessHandler;
import com.example.campusmarket.payment.application.RefundService;
import com.example.campusmarket.shared.DomainEvent;
import com.example.campusmarket.shared.Money;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Reliable Rabbit consumer for warranty events. InboxRepository supplies event-id fencing. */
@Component
@Profile("!test")
public final class WarrantyEventBusinessHandler implements EventBusinessHandler {
    private final RefundService refunds;

    public WarrantyEventBusinessHandler(RefundService refunds) {
        this.refunds = refunds;
    }

    @Override
    public void handle(DomainEvent event) {
        if (!"WARRANTY_REFUND_REQUESTED".equals(event.eventType())) return;
        try {
            UUID orderId = UUID.fromString(String.valueOf(event.payload().get("orderId")));
            UUID caseId = UUID.fromString(String.valueOf(event.payload().get("caseId")));
            UUID obligationId = UUID.fromString(String.valueOf(event.payload().get("obligationId")));
            long amount = ((Number) event.payload().get("amountFen")).longValue();
            refunds.requestRefund(orderId, "warranty-refund-" + obligationId, Money.ofFen(amount), "WARRANTY", caseId);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("质保退款事件处理失败", ex);
        }
    }
}
