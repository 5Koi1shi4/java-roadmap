package com.example.order.domain;

public record Order(long id, long productId, int quantity, OrderStatus status) {
}
