package com.example.campusmarket.product.event;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 商品读服务自己的消息模型；市场服务的 Java 类不进入生产依赖。 */
public record ProductSnapshotEvent(
    UUID eventId,
    UUID listingId,
    long aggregateVersion,
    String eventType,
    Instant occurredAt,
    int schemaVersion,
    ProductSnapshot snapshot
) {
    private static final Set<String> EVENT_TYPES = Set.of(
        "LISTING_CREATED", "LISTING_UPDATED", "LISTING_PUBLISHED",
        "LISTING_OFF_SALE", "LISTING_SOLD_OUT", "INVENTORY_CHANGED");

    public ProductSnapshotEvent {
        Objects.requireNonNull(eventId, "事件ID不能为空");
        Objects.requireNonNull(listingId, "商品ID不能为空");
        if (aggregateVersion <= 0) throw new IllegalArgumentException("商品版本必须为正数");
        if (eventType == null || !EVENT_TYPES.contains(eventType)) {
            throw new IllegalArgumentException("商品事件类型无效");
        }
        Objects.requireNonNull(occurredAt, "事件时间不能为空");
        if (schemaVersion != 2) throw new IllegalArgumentException("商品事件版本不支持");
        Objects.requireNonNull(snapshot, "商品快照不能为空");
    }

    public record ProductSnapshot(
        String title,
        String description,
        String category,
        long unitPriceFen,
        int availableQuantity,
        String status
    ) {
        private static final Set<String> STATUSES = Set.of("DRAFT", "ON_SALE", "SOLD_OUT", "OFF_SALE");

        public ProductSnapshot {
            requireText(title, "标题");
            requireText(description, "描述");
            requireText(category, "分类");
            if (unitPriceFen < 0 || availableQuantity < 0) {
                throw new IllegalArgumentException("商品金额或库存无效");
            }
            if (status == null || !STATUSES.contains(status)) {
                throw new IllegalArgumentException("商品状态无效");
            }
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
