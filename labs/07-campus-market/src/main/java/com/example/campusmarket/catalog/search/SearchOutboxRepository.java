package com.example.campusmarket.catalog.search;

import com.example.campusmarket.catalog.domain.Listing;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 商品事实更新事务内写入的搜索 outbox。 */
@Repository
public class SearchOutboxRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SearchOutboxRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.mapper = Objects.requireNonNull(mapper, "ObjectMapper不能为空");
    }

    public void enqueue(Listing listing, String eventType) {
        Objects.requireNonNull(listing, "商品不能为空");
        enqueue(listing.id(), listing.version(), eventType);
    }

    public void enqueue(UUID listingId, long aggregateVersion, String eventType) {
        Objects.requireNonNull(listingId, "商品ID不能为空");
        if (aggregateVersion <= 0 || eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("搜索事件参数无效");
        }
        try {
            String payload = mapper.writeValueAsString(Map.of("listingId", listingId.toString()));
            Instant now = Instant.now();
            jdbc.update("""
                INSERT INTO search_outbox(id,listing_id,aggregate_version,event_type,payload,status,attempt_count,available_at,created_at)
                VALUES (?,?,?,?,CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),?)
                """, UUID.randomUUID().toString(), listingId.toString(), aggregateVersion, eventType, payload,
                Timestamp.from(now));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("搜索事件序列化失败", e);
        }
    }
}
