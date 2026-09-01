package com.example.campusmarket.catalog.search;

import java.util.Set;

/** 搜索事件和索引状态的唯一白名单，供 outbox、投影和重建共同使用。 */
public final class SearchSchema {
    public static final String TOMBSTONE = "TOMBSTONE";
    private static final Set<String> EVENT_TYPES = Set.of(
        "LISTING_CREATED", "LISTING_UPDATED", "LISTING_PUBLISHED", "LISTING_OFF_SALE",
        "LISTING_SOLD_OUT", "INVENTORY_CHANGED");
    private static final Set<String> STATUSES = Set.of("DRAFT", "ON_SALE", "SOLD_OUT", "OFF_SALE", TOMBSTONE);

    private SearchSchema() { }
    public static String requireEventType(String type) {
        if (type == null || !EVENT_TYPES.contains(type)) throw new IllegalArgumentException("搜索事件类型无效");
        return type;
    }
    public static String requireStatus(String status) {
        if (status == null || !STATUSES.contains(status)) throw new IllegalArgumentException("搜索状态无效");
        return status;
    }
}
