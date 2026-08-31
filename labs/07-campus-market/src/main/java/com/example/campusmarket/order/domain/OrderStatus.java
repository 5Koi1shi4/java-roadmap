package com.example.campusmarket.order.domain;

public enum OrderStatus {
    PENDING_PAYMENT,
    AWAITING_HANDOFF,
    AWAITING_RECEIPT,
    AFTERSALE_WINDOW,
    DISPUTED,
    REFUNDING_CANCEL,
    CANCELLED,
    REFUNDED,
    SETTLED
}
