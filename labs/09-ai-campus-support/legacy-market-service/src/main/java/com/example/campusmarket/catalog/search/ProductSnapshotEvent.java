package com.example.campusmarket.catalog.search;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 商品读模型使用的版本化完整快照事件；不得携带媒体、对象存储或身份信息。 */
public record ProductSnapshotEvent(
    UUID eventId,
    UUID listingId,
    long aggregateVersion,
    String eventType,
    Instant occurredAt,
    int schemaVersion,
    ProductSnapshot snapshot
) {
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public ProductSnapshotEvent {
        Objects.requireNonNull(eventId, "事件ID不能为空");
        Objects.requireNonNull(listingId, "商品ID不能为空");
        if (aggregateVersion <= 0) throw new IllegalArgumentException("聚合版本必须为正数");
        SearchSchema.requireEventType(eventType);
        Objects.requireNonNull(occurredAt, "事件时间不能为空");
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("仅支持商品快照 schemaVersion=2");
        }
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
        private static final Set<String> LISTING_STATUSES = Set.of("DRAFT", "ON_SALE", "SOLD_OUT", "OFF_SALE");

        public ProductSnapshot {
            title = requireText(title, "标题");
            description = requireText(description, "描述");
            category = requireText(category, "分类");
            if (unitPriceFen < 0) throw new IllegalArgumentException("单价不能为负数");
            if (availableQuantity < 0) throw new IllegalArgumentException("可售库存不能为负数");
            if (status == null || !LISTING_STATUSES.contains(status)) {
                throw new IllegalArgumentException("商品状态无效");
            }
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(field + "不能为空");
            return value;
        }
    }
}
