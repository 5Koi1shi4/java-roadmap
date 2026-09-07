package com.example.campusmarket.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** integration_outbox 的条件领取与 token fencing。 */
@Repository
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
            SELECT id,event_id,event_type,aggregate_id,aggregate_version,schema_version,payload,occurred_at,
                   owner_id,claim_token,lease_until,attempt_count,status
            FROM integration_outbox
            WHERE ((status='NEW' AND available_at <= CURRENT_TIMESTAMP(6))
               OR (status='PUBLISHING' AND lease_until <= CURRENT_TIMESTAMP(6)))
            ORDER BY available_at,id
            LIMIT ? FOR UPDATE SKIP LOCKED
            """, rs -> {
                UUID id = UUID.fromString(rs.getString("id"));
                UUID eventId = UUID.fromString(rs.getString("event_id"));
                String token = UUID.randomUUID().toString();
                boolean exhausted = "PUBLISHING".equals(rs.getString("status")) && rs.getInt("attempt_count") >= 3;
                int changed = jdbc.update("""
                    UPDATE integration_outbox
                    SET status='PUBLISHING', owner_id=?, claim_token=?,
                        lease_until=TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)),
                        attempt_count=attempt_count+?
                    WHERE id=? AND ((status='NEW' AND available_at <= CURRENT_TIMESTAMP(6))
                        OR (status='PUBLISHING' AND lease_until <= CURRENT_TIMESTAMP(6)))
                        AND (attempt_count < 3 OR (status='PUBLISHING' AND attempt_count >= 3))
                    """, owner, token, micros, exhausted ? 0 : 1, id.toString());
                if (changed == 1) {
                    claimed.add(new OutboxMessage(id, eventId, rs.getString("event_type"),
                        rs.getString("aggregate_id"), rs.getLong("aggregate_version"),
                        rs.getInt("schema_version"), rs.getString("payload"), rs.getTimestamp("occurred_at").toInstant(),
                        owner, token, null, exhausted ? rs.getInt("attempt_count") : rs.getInt("attempt_count") + 1,
                        exhausted));
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
              AND lease_until > CURRENT_TIMESTAMP(6)
            """, eventId.toString(), owner, claimToken);
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
              AND lease_until > CURRENT_TIMESTAMP(6) AND attempt_count < 3
            """, micros, eventId.toString(), owner, claimToken);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int fail(UUID eventId, String owner, String claimToken, String failureClass) {
        Objects.requireNonNull(eventId, "eventId 不能为空");
        requireOwner(owner);
        Objects.requireNonNull(claimToken, "claim token 不能为空");
        if (!"PERMANENT".equals(failureClass) && !"EXHAUSTED".equals(failureClass)) {
            throw new IllegalArgumentException("failureClass 无效");
        }
        int changed = jdbc.update("""
            UPDATE integration_outbox
            SET status='FAILED', failure_class=?, owner_id=NULL, claim_token=NULL, lease_until=NULL
            WHERE event_id=? AND status='PUBLISHING' AND owner_id=? AND claim_token=?
              AND lease_until > CURRENT_TIMESTAMP(6)
            """, failureClass, eventId.toString(), owner, claimToken);
        if (changed == 1) {
            jdbc.update("""
                INSERT INTO manual_failure (id,source_type,source_id,consumer_name,failure_class,payload,status,created_at)
                SELECT ?, 'OUTBOX', event_id, '', ?, payload, 'NEW', CURRENT_TIMESTAMP(6)
                FROM integration_outbox
                WHERE event_id=? AND status='FAILED' AND failure_class=?
                ON DUPLICATE KEY UPDATE id=manual_failure.id
                """, UUID.randomUUID().toString(), failureClass, eventId.toString(), failureClass);
        }
        return changed;
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
                                long aggregateVersion, int schemaVersion, String payloadJson, Instant occurredAt,
                                String ownerId, String claimToken, Instant leaseUntil, int attemptCount,
                                boolean exhaustedTakeover) {
        public OutboxMessage {
            Objects.requireNonNull(id, "id 不能为空");
            Objects.requireNonNull(eventId, "eventId 不能为空");
            Objects.requireNonNull(eventType, "eventType 不能为空");
            Objects.requireNonNull(aggregateId, "aggregateId 不能为空");
            if (aggregateVersion <= 0 || schemaVersion != 1) throw new IllegalArgumentException("事件版本无效");
            Objects.requireNonNull(payloadJson, "payload 不能为空");
            Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
            Objects.requireNonNull(ownerId, "owner 不能为空");
            Objects.requireNonNull(claimToken, "claim token 不能为空");
            if (attemptCount <= 0) throw new IllegalArgumentException("attemptCount 无效");
        }

        private OutboxMessage withLeaseUntil(Instant value) {
            return new OutboxMessage(id, eventId, eventType, aggregateId, aggregateVersion,
                schemaVersion, payloadJson, occurredAt, ownerId, claimToken, value, attemptCount, exhaustedTakeover);
        }
    }
}
