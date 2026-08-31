package com.example.campusmarket.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** integration_outbox 的条件领取与 token fencing。 */
@Repository
@ConditionalOnBean(JdbcTemplate.class)
public class OutboxRepository {
    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC 不能为空");
    }

    @Transactional
    public List<OutboxMessage> claimBatch(String owner, int limit, Duration lease) {
        requireOwner(owner);
        if (limit <= 0 || limit > 1000) {
            throw new IllegalArgumentException("领取数量必须在 1 到 1000 之间");
        }
        long micros = leaseMicros(lease);
        List<OutboxMessage> claimed = new ArrayList<>();
        jdbc.query("""
            SELECT id,event_id,event_type,aggregate_id,aggregate_version,schema_version,payload,
                   owner_id,claim_token,lease_until
            FROM integration_outbox
            WHERE (status='NEW' AND available_at <= CURRENT_TIMESTAMP(6))
               OR (status='PUBLISHING' AND lease_until < CURRENT_TIMESTAMP(6))
            ORDER BY available_at,id
            LIMIT ? FOR UPDATE SKIP LOCKED
            """, rs -> {
                UUID id = UUID.fromString(rs.getString("id"));
                UUID eventId = UUID.fromString(rs.getString("event_id"));
                String token = UUID.randomUUID().toString();
                int changed = jdbc.update("""
                    UPDATE integration_outbox
                    SET status='PUBLISHING', owner_id=?, claim_token=?,
                        lease_until=TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)),
                        attempt_count=attempt_count+1
                    WHERE id=? AND ((status='NEW' AND available_at <= CURRENT_TIMESTAMP(6))
                        OR (status='PUBLISHING' AND lease_until < CURRENT_TIMESTAMP(6)))
                    """, owner, token, micros, id.toString());
                if (changed == 1) {
                    claimed.add(new OutboxMessage(id, eventId, rs.getString("event_type"),
                        rs.getString("aggregate_id"), rs.getLong("aggregate_version"),
                        rs.getInt("schema_version"), rs.getString("payload"), owner, token,
                        null));
                }
            }, limit);
        if (!claimed.isEmpty()) {
            for (int i = 0; i < claimed.size(); i++) {
                OutboxMessage message = claimed.get(i);
                Instant leaseUntil = jdbc.queryForObject(
                    "SELECT lease_until FROM integration_outbox WHERE id=? AND owner_id=? AND claim_token=?",
                    (rs, rowNum) -> rs.getTimestamp(1).toInstant(), message.id().toString(), owner, message.claimToken());
                claimed.set(i, message.withLeaseUntil(leaseUntil));
            }
        }
        return List.copyOf(claimed);
    }

    /** 只有当前 owner 与 token 能完成发布，旧 owner 的迟到确认影响 0 行。 */
    @Transactional
    public int complete(UUID eventId, String owner, String claimToken) {
        Objects.requireNonNull(eventId, "eventId 不能为空");
        requireOwner(owner);
        Objects.requireNonNull(claimToken, "claim token 不能为空");
        return jdbc.update("""
            UPDATE integration_outbox
            SET status='PUBLISHED', published_at=CURRENT_TIMESTAMP(6),
                owner_id=NULL, claim_token=NULL, lease_until=NULL
            WHERE event_id=? AND status='PUBLISHING' AND owner_id=? AND claim_token=?
            """, eventId.toString(), owner, claimToken);
    }

    /** 兼容只持有 token 的调用方；随机 token 本身仍保证旧领取无法完成。 */
    @Transactional
    public int complete(UUID eventId, String claimToken) {
        Objects.requireNonNull(eventId, "eventId 不能为空");
        Objects.requireNonNull(claimToken, "claim token 不能为空");
        return jdbc.update("""
            UPDATE integration_outbox
            SET status='PUBLISHED', published_at=CURRENT_TIMESTAMP(6),
                owner_id=NULL, claim_token=NULL, lease_until=NULL
            WHERE event_id=? AND status='PUBLISHING' AND claim_token=? AND owner_id IS NOT NULL
            """, eventId.toString(), claimToken);
    }

    @Transactional
    public int releaseForRetry(UUID eventId, String owner, String claimToken, Duration delay) {
        Objects.requireNonNull(eventId, "eventId 不能为空");
        requireOwner(owner);
        Objects.requireNonNull(claimToken, "claim token 不能为空");
        long micros = leaseMicros(delay);
        return jdbc.update("""
            UPDATE integration_outbox
            SET status='NEW', owner_id=NULL, claim_token=NULL, lease_until=NULL,
                available_at=TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6))
            WHERE event_id=? AND status='PUBLISHING' AND owner_id=? AND claim_token=?
            """, micros, eventId.toString(), owner, claimToken);
    }

    private static long leaseMicros(Duration duration) {
        Objects.requireNonNull(duration, "时长不能为空");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("时长必须为正数");
        }
        try {
            long micros = Math.addExact(Math.multiplyExact(duration.getSeconds(), 1_000_000L),
                duration.getNano() / 1_000L);
            if (micros <= 0) throw new IllegalArgumentException("时长过小");
            return micros;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("时长溢出", e);
        }
    }

    private static void requireOwner(String owner) {
        Objects.requireNonNull(owner, "owner 不能为空");
        if (owner.isBlank() || owner.length() > 100) throw new IllegalArgumentException("owner 无效");
    }

    public record OutboxMessage(UUID id, UUID eventId, String eventType, String aggregateId,
                                long aggregateVersion, int schemaVersion, String payloadJson,
                                String ownerId, String claimToken, Instant leaseUntil) {
        public OutboxMessage {
            Objects.requireNonNull(id, "id 不能为空");
            Objects.requireNonNull(eventId, "eventId 不能为空");
            Objects.requireNonNull(eventType, "eventType 不能为空");
            Objects.requireNonNull(aggregateId, "aggregateId 不能为空");
            if (aggregateVersion <= 0 || schemaVersion != 1) throw new IllegalArgumentException("事件版本无效");
            Objects.requireNonNull(payloadJson, "payload 不能为空");
            Objects.requireNonNull(ownerId, "owner 不能为空");
            Objects.requireNonNull(claimToken, "claim token 不能为空");
        }

        private OutboxMessage withLeaseUntil(Instant value) {
            return new OutboxMessage(id, eventId, eventType, aggregateId, aggregateVersion,
                schemaVersion, payloadJson, ownerId, claimToken, value);
        }
    }
}
