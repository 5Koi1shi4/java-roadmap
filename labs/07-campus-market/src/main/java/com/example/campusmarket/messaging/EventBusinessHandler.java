package com.example.campusmarket.messaging;

import com.example.campusmarket.shared.DomainEvent;

@FunctionalInterface
public interface EventBusinessHandler {
    void handle(DomainEvent event);
}
