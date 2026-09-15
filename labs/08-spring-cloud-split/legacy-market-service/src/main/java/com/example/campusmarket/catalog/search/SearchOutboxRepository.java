package com.example.campusmarket.catalog.search;

import com.example.campusmarket.catalog.domain.Listing;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;
import java.time.Instant;

/** 商品事实更新事务内写入的搜索 outbox。 */
@Repository
@Profile("!test")
public class SearchOutboxRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SearchGateRepository gate;

    @Autowired
    public SearchOutboxRepository(JdbcTemplate jdbc, ObjectMapper mapper, SearchGateRepository gate) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.mapper = Objects.requireNonNull(mapper, "ObjectMapper不能为空");
        this.gate = Objects.requireNonNull(gate, "搜索门禁不能为空");
    }

    @Transactional
    public void enqueue(Listing listing, String eventType) {
        Objects.requireNonNull(listing, "商品不能为空");
        enqueue(listing.id(), listing.version(), eventType);
    }

    @Transactional
    public void enqueue(UUID listingId, long aggregateVersion, String eventType) {
        Objects.requireNonNull(listingId, "商品ID不能为空");
        if (aggregateVersion <= 0 || eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("搜索事件参数无效");
        }
        SearchSchema.requireEventType(eventType);
        try {
            gate.assertWritable();
            ProductSnapshotEvent event = snapshotEvent(listingId, aggregateVersion, eventType);
            String payload = mapper.writeValueAsString(event);
            jdbc.update("""
                INSERT INTO search_outbox(id,listing_id,aggregate_version,event_type,payload,schema_version,
                                          status,attempt_count,available_at,created_at)
                VALUES (?,?,?,?,CAST(? AS JSON),2,'NEW',0,CURRENT_TIMESTAMP(6),DEFAULT)
                """, event.eventId().toString(), event.listingId().toString(), event.aggregateVersion(),
                event.eventType(), payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("搜索事件序列化失败", e);
        }
    }

    private ProductSnapshotEvent snapshotEvent(UUID listingId, long aggregateVersion, String eventType) {
        return jdbc.query("""
            SELECT id,title,description,category,unit_price_fen,available_quantity,status,version
            FROM listing WHERE id=? FOR UPDATE
            """, rs -> {
                if (!rs.next()) throw new IllegalArgumentException("商品不存在");
                long actualVersion = rs.getLong("version");
                if (actualVersion != aggregateVersion) {
                    throw new IllegalArgumentException("商品版本与快照版本不一致");
                }
                UUID actualListingId = UUID.fromString(rs.getString("id"));
                ProductSnapshotEvent.ProductSnapshot snapshot = new ProductSnapshotEvent.ProductSnapshot(
                    rs.getString("title"),
                    rs.getString("description"),
                    rs.getString("category"),
                    rs.getLong("unit_price_fen"),
                    rs.getInt("available_quantity"),
                    rs.getString("status"));
                return new ProductSnapshotEvent(UUID.randomUUID(), actualListingId, aggregateVersion, eventType,
                    Instant.now(), ProductSnapshotEvent.CURRENT_SCHEMA_VERSION, snapshot);
            }, listingId.toString());
    }
}
