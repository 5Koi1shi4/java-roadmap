package com.example.campusmarket.order.application;

import java.util.UUID;

public record CreateOrderCommand(UUID listingId, int quantity) {
    public CreateOrderCommand {
        if (listingId == null) throw new IllegalArgumentException("商品ID不能为空");
        if (quantity <= 0) throw new IllegalArgumentException("购买数量必须为正数");
    }
}
