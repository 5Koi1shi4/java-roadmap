package com.example.campusmarket.catalog.search;

import com.example.campusmarket.shared.DomainEvent;
import com.example.campusmarket.observability.CampusMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 搜索 outbox 的租约调度器；完成、重试和失败均由 owner+claim token fencing。 */
@Component
public class SearchOutboxDispatcher {
    private final JdbcTemplate jdbc;
    private final SearchProjector projector;
    private final ObjectMapper mapper;
    private final SearchOutboxClaimer claimer;
    private final String owner = "search-dispatcher-" + UUID.randomUUID();
    private final CampusMetrics metrics;

    public SearchOutboxDispatcher(JdbcTemplate jdbc, SearchProjector projector, ObjectMapper mapper, SearchOutboxClaimer claimer) {
        this(jdbc, projector, mapper, claimer, null);
    }

    @Autowired
    public SearchOutboxDispatcher(JdbcTemplate jdbc, SearchProjector projector, ObjectMapper mapper, SearchOutboxClaimer claimer,
                                  CampusMetrics metrics) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.projector = Objects.requireNonNull(projector, "投影器不能为空");
        this.mapper = Objects.requireNonNull(mapper, "ObjectMapper不能为空");
        this.claimer = Objects.requireNonNull(claimer, "领取器不能为空");
        this.metrics = metrics;
    }

    public int dispatchOnce(int limit) { return dispatchOnce(limit, Duration.ofSeconds(30)); }

    public int dispatchOnce(int limit, Duration lease) {
        final List<Claim> claims;
        try {
            claims = claimer.claim(owner, limit, lease);
        } catch (SearchGateRepository.SearchGateClosedException paused) {
            // 重建是预期的暂停。没有领取行，因此门禁关闭期间不消耗投递尝试次数。
            return 0;
        }
        int completed = 0;
        for (Claim claim : claims) {
            try {
                DomainEvent event = new DomainEvent(UUID.fromString(claim.id()), claim.eventType(), claim.listingId().toString(),
                    claim.aggregateVersion(), claim.createdAt(), 1, decode(claim.payload()));
                projector.project(event);
                int changed = complete(claim);
                completed += changed;
                if (changed == 1 && metrics != null) metrics.recordOutbox("PUBLISHED");
            } catch (RuntimeException failure) {
                if (failure instanceof SearchGateRepository.SearchGateClosedException) defer(claim);
                else if (claim.attemptCount() >= 3) { if (fail(claim) == 1 && metrics != null) metrics.recordOutbox("FAILED"); }
                else { if (releaseForRetry(claim) == 1 && metrics != null) metrics.recordRetry("OUTBOX", "RETRY"); }
            }
        }
        return completed;
    }

    private int complete(Claim claim) {
        return jdbc.update("""
            UPDATE search_outbox SET status='PUBLISHED',owner_id=NULL,claim_token=NULL,lease_until=NULL
            WHERE id=? AND status='PUBLISHING' AND owner_id=? AND claim_token=? AND lease_until > CURRENT_TIMESTAMP(6)
            """, claim.id(), owner, claim.claimToken());
    }

    private int releaseForRetry(Claim claim) {
        return jdbc.update("""
            UPDATE search_outbox SET status='NEW',owner_id=NULL,claim_token=NULL,lease_until=NULL,
                available_at=TIMESTAMPADD(SECOND,1,CURRENT_TIMESTAMP(6))
            WHERE id=? AND status='PUBLISHING' AND owner_id=? AND claim_token=? AND lease_until > CURRENT_TIMESTAMP(6) AND attempt_count < 3
            """, claim.id(), owner, claim.claimToken());
    }

    /** 重建门禁是预期的临时状态，不属于投递失败。 */
    private int defer(Claim claim) {
        return jdbc.update("""
            UPDATE search_outbox SET status='NEW',owner_id=NULL,claim_token=NULL,lease_until=NULL,
                attempt_count=GREATEST(attempt_count-1,0),available_at=TIMESTAMPADD(SECOND,1,CURRENT_TIMESTAMP(6))
            WHERE id=? AND status='PUBLISHING' AND owner_id=? AND claim_token=? AND lease_until > CURRENT_TIMESTAMP(6)
            """, claim.id(), owner, claim.claimToken());
    }

    private int fail(Claim claim) {
        return jdbc.update("""
            UPDATE search_outbox SET status='FAILED',owner_id=NULL,claim_token=NULL,lease_until=NULL
            WHERE id=? AND status='PUBLISHING' AND owner_id=? AND claim_token=? AND lease_until > CURRENT_TIMESTAMP(6)
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
