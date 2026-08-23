package com.example.order.application;

public record StoredOutbox(String eventType, String status, int version) {
}
