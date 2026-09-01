package com.example.campusmarket.catalog.search;

import com.example.campusmarket.shared.DomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 搜索 outbox 的租约调度器；完成、重试和失败均由 owner+claim token fencing。 */
@Component
public class SearchOutboxDispatcher {
    private final JdbcTemplate jdbc;
    private final SearchProjector projector;
    private final ObjectMapper mapper;
    private final String owner = "search-dispatcher-" + UUID.randomUUID();

    public SearchOutboxDispatcher(JdbcTemplate jdbc, SearchProjector projector, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.projector = Objects.requireNonNull(projector, "投影器不能为空");
        this.mapper = Objects.requireNonNull(mapper, "ObjectMapper不能为空");
    }

    public int dispatchOnce(int limit) { return dispatchOnce(limit, Duration.ofSeconds(30)); }

    public int dispatchOnce(int limit, Duration lease) {
        List<Claim> claims = claimBatch(limit, lease);
        int completed = 0;
        for (Claim claim : claims) {
            try {
                DomainEvent event = new DomainEvent(UUID.fromString(claim.id()), claim.eventType(), claim.listingId().toString(),
                    claim.aggregateVersion(), claim.createdAt(), 1, decode(claim.payload()));
                projector.project(event);
                completed += complete(claim);
            } catch (RuntimeException failure) {
                if (claim.attemptCount() >= 3) fail(claim);
                else releaseForRetry(claim);
            }
        }
        return completed;
    }

    @Transactional
    public List<Claim> claimBatch(int limit, Duration lease) {
        if (limit <= 0 || limit > 1000) throw new IllegalArgumentException("领取数量必须在1到1000之间");
        if (lease == null || lease.isNegative() || lease.isZero()) throw new IllegalArgumentException("租约必须为正数");
        long micros = Math.addExact(Math.multiplyExact(lease.getSeconds(), 1_000_000L), lease.getNano() / 1_000L);
        List<Claim> result = new ArrayList<>();
        jdbc.query("""
            SELECT id,listing_id,aggregate_version,event_type,payload,created_at,attempt_count
            FROM search_outbox
            WHERE (status='NEW' AND available_at <= CURRENT_TIMESTAMP(6))
               OR (status='PUBLISHING' AND lease_until <= CURRENT_TIMESTAMP(6))
            ORDER BY available_at,id LIMIT ? FOR UPDATE SKIP LOCKED
            """, rs -> {
                String id = rs.getString("id");
                String token = UUID.randomUUID().toString();
                int attempts = rs.getInt("attempt_count") + 1;
                int changed = jdbc.update("""
                    UPDATE search_outbox SET status='PUBLISHING',owner_id=?,claim_token=?,
                        lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),attempt_count=?
                    WHERE id=? AND ((status='NEW' AND available_at <= CURRENT_TIMESTAMP(6))
                       OR (status='PUBLISHING' AND lease_until <= CURRENT_TIMESTAMP(6)))
                    """, owner, token, micros, attempts, id);
                if (changed == 1) result.add(new Claim(id, UUID.fromString(rs.getString("listing_id")),
                    rs.getLong("aggregate_version"), rs.getString("event_type"), rs.getString("payload"),
                    rs.getTimestamp("created_at").toInstant(), owner, token, attempts));
            }, limit);
        return List.copyOf(result);
    }

    private int complete(Claim claim) {
        return jdbc.update("""
            UPDATE search_outbox SET status='PUBLISHED',owner_id=NULL,claim_token=NULL,lease_until=NULL
            WHERE id=? AND status='PUBLISHING' AND owner_id=? AND claim_token=?
            """, claim.id(), owner, claim.claimToken());
    }

    private int releaseForRetry(Claim claim) {
        return jdbc.update("""
            UPDATE search_outbox SET status='NEW',owner_id=NULL,claim_token=NULL,lease_until=NULL,
                available_at=TIMESTAMPADD(SECOND,1,CURRENT_TIMESTAMP(6))
            WHERE id=? AND status='PUBLISHING' AND owner_id=? AND claim_token=? AND attempt_count < 3
            """, claim.id(), owner, claim.claimToken());
    }

    private int fail(Claim claim) {
        return jdbc.update("""
            UPDATE search_outbox SET status='FAILED',owner_id=NULL,claim_token=NULL,lease_until=NULL
            WHERE id=? AND status='PUBLISHING' AND owner_id=? AND claim_token=?
            """, claim.id(), owner, claim.claimToken());
    }

    private java.util.Map<String, Object> decode(String payload) {
        try {
            return mapper.readValue(payload, mapper.getTypeFactory().constructMapType(java.util.Map.class,
                String.class, Object.class));
        } catch (Exception e) {
            throw new IllegalArgumentException("搜索事件 payload 无效", e);
        }
    }

    public record Claim(String id, UUID listingId, long aggregateVersion, String eventType, String payload,
                        Instant createdAt, String ownerId, String claimToken, int attemptCount) {
        public Claim {
            Objects.requireNonNull(id); Objects.requireNonNull(listingId); Objects.requireNonNull(eventType);
            Objects.requireNonNull(payload); Objects.requireNonNull(createdAt); Objects.requireNonNull(ownerId);
            Objects.requireNonNull(claimToken);
            if (aggregateVersion <= 0 || attemptCount <= 0) throw new IllegalArgumentException("搜索事件领取参数无效");
        }
    }
}
