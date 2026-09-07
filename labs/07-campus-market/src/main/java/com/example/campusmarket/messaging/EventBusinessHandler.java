package com.example.campusmarket.messaging;

import com.example.campusmarket.shared.DomainEvent;

@FunctionalInterface
public interface EventBusinessHandler {
    void handle(DomainEvent event);

    /** Explicit routing contract used when several handler beans coexist. */
    /** Existing generic handlers own non-warranty events unless they opt out. */
    default boolean supports(String eventType) { return !eventType.startsWith("WARRANTY_"); }

    /** Handler did not own this routing key; reject it without completing Inbox. */
    class UnsupportedEventException extends RuntimeException {
        public UnsupportedEventException(String eventType) { super("未注册的业务事件: " + eventType); }
    }
}
