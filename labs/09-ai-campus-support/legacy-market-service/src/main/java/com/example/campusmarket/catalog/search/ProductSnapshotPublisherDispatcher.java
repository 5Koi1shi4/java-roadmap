package com.example.campusmarket.catalog.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 从源 search_outbox 领取并以 Rabbit confirm 驱动 PUBLISHED。 */
@Component
@Profile("!test")
public final class ProductSnapshotPublisherDispatcher {
    private static final Duration DEFAULT_LEASE = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    private final ProductSnapshotOutboxClaimer claimer;
    private final ProductEventPublisher publisher;
    private final ObjectMapper mapper;
    private final String owner = "product-publisher-" + UUID.randomUUID();

    public ProductSnapshotPublisherDispatcher(ProductSnapshotOutboxClaimer claimer,
                                               ProductEventPublisher publisher,
                                               ObjectMapper mapper) {
        this.claimer = Objects.requireNonNull(claimer, "领取器不能为空");
        this.publisher = Objects.requireNonNull(publisher, "商品事件发布器不能为空");
        this.mapper = Objects.requireNonNull(mapper, "ObjectMapper 不能为空");
    }

    public int dispatchOnce(int limit) {
        return dispatchOnce(limit, DEFAULT_LEASE);
    }

    public int dispatchOnce(int limit, Duration lease) {
        if (limit <= 0 || limit > 1000 || lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("发布参数无效");
        }
        // Broker 不健康时不领取，避免 outage 期间无意义地消耗 attempt_count。
        if (!publisher.brokerHealthy()) {
            return 0;
        }
        List<ProductSnapshotOutboxClaimer.Claim> claims = claimer.claim(owner, limit, lease);
        int completed = 0;
        for (ProductSnapshotOutboxClaimer.Claim claim : claims) {
            try {
                ProductSnapshotEvent event = decodeAndCheck(claim);
                publisher.publish(event);
                completed += claimer.complete(claim);
            } catch (IllegalArgumentException malformed) {
                claimer.fail(claim, "PERMANENT");
            } catch (RuntimeException failure) {
                if (claim.attemptCount() >= 3) {
                    claimer.fail(claim, "EXHAUSTED");
                } else {
                    claimer.releaseForRetry(claim, RETRY_DELAY);
                }
            }
        }
        return completed;
    }

    private ProductSnapshotEvent decodeAndCheck(ProductSnapshotOutboxClaimer.Claim claim) {
        final ProductSnapshotEvent event;
        try {
            event = mapper.readValue(claim.payload(), ProductSnapshotEvent.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException("商品快照 payload 无效", e);
        }
        if (!claim.id().equals(event.eventId().toString())
            || !claim.listingId().equals(event.listingId())
            || claim.aggregateVersion() != event.aggregateVersion()
            || !claim.eventType().equals(event.eventType())
            || claim.schemaVersion() != event.schemaVersion()) {
            throw new IllegalArgumentException("商品快照 payload 与 outbox 元数据不一致");
        }
        return event;
    }
}
