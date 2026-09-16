package com.example.campusmarket.catalog.search;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 商品快照源 outbox 的数据库时间租约与 claim token fencing。 */
@Service
@Profile("!test")
public class ProductSnapshotOutboxClaimer {
    private final JdbcTemplate jdbc;

    public ProductSnapshotOutboxClaimer(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC 不能为空");
    }

    @Transactional
    public List<Claim> claim(String owner, int limit, Duration lease) {
        requireOwner(owner);
        long leaseMicros = leaseMicros(lease);
        List<Claim> result = new ArrayList<>();

        // 已经尝试三次的过期租约进入可检查失败终态；事件行仍保留，
        // replay 可以按 sequence_no 重新发布。
        jdbc.update("""
            UPDATE search_outbox
            SET status='FAILED', failure_class='EXHAUSTED', owner_id=NULL, claim_token=NULL, lease_until=NULL
            WHERE status='PUBLISHING' AND lease_until <= CURRENT_TIMESTAMP(6) AND attempt_count >= 3
            """);

        jdbc.query("""
            SELECT id,listing_id,aggregate_version,event_type,payload,schema_version,created_at,attempt_count
            FROM search_outbox
            WHERE ((status='NEW' AND available_at <= CURRENT_TIMESTAMP(6))
                OR (status='PUBLISHING' AND lease_until <= CURRENT_TIMESTAMP(6)))
              AND attempt_count < 3
            ORDER BY sequence_no
            LIMIT ? FOR UPDATE SKIP LOCKED
            """, rs -> {
                String id = rs.getString("id");
                String token = UUID.randomUUID().toString();
                int attempt = rs.getInt("attempt_count") + 1;
                int changed = jdbc.update("""
                    UPDATE search_outbox
                    SET status='PUBLISHING', failure_class=NULL, owner_id=?, claim_token=?,
                        lease_until=TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)),
                        attempt_count=?
                    WHERE id=?
                      AND ((status='NEW' AND available_at <= CURRENT_TIMESTAMP(6))
                        OR (status='PUBLISHING' AND lease_until <= CURRENT_TIMESTAMP(6)))
                      AND attempt_count < 3
                    """, owner, token, leaseMicros, attempt, id);
                if (changed == 1) {
                    result.add(new Claim(id, UUID.fromString(rs.getString("listing_id")),
                        rs.getLong("aggregate_version"), rs.getString("event_type"),
                        rs.getString("payload"), rs.getInt("schema_version"),
                        rs.getTimestamp("created_at").toInstant(), owner, token, attempt));
                }
            }, limit);
        return List.copyOf(result);
    }

    /** 只有当前 owner/token 且租约仍有效时才允许确认发布。 */
    @Transactional
    public int complete(Claim claim) {
        Objects.requireNonNull(claim, "领取记录不能为空");
        return jdbc.update("""
            UPDATE search_outbox
            SET status='PUBLISHED', owner_id=NULL, claim_token=NULL, lease_until=NULL
            WHERE id=? AND status='PUBLISHING' AND owner_id=? AND claim_token=?
              AND lease_until > CURRENT_TIMESTAMP(6)
            """, claim.id(), claim.ownerId(), claim.claimToken());
    }

    @Transactional
    public int releaseForRetry(Claim claim, Duration delay) {
        Objects.requireNonNull(claim, "领取记录不能为空");
        long delayMicros = leaseMicros(delay);
        return jdbc.update("""
            UPDATE search_outbox
            SET status='NEW', owner_id=NULL, claim_token=NULL, lease_until=NULL,
                available_at=TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6))
            WHERE id=? AND status='PUBLISHING' AND owner_id=? AND claim_token=?
              AND lease_until > CURRENT_TIMESTAMP(6) AND attempt_count < 3
            """, delayMicros, claim.id(), claim.ownerId(), claim.claimToken());
    }

    /** 失败终态保留源行，供本机 replay 检查和重放。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int fail(Claim claim, String failureClass) {
        Objects.requireNonNull(claim, "领取记录不能为空");
        if (!"PERMANENT".equals(failureClass) && !"EXHAUSTED".equals(failureClass)) {
            throw new IllegalArgumentException("failureClass 无效");
        }
        return jdbc.update("""
            UPDATE search_outbox
            SET status='FAILED', failure_class=?, owner_id=NULL, claim_token=NULL, lease_until=NULL
            WHERE id=? AND status='PUBLISHING' AND owner_id=? AND claim_token=?
              AND lease_until > CURRENT_TIMESTAMP(6)
            """, failureClass, claim.id(), claim.ownerId(), claim.claimToken());
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

    public record Claim(String id, UUID listingId, long aggregateVersion, String eventType, String payload,
                        int schemaVersion, Instant createdAt, String ownerId, String claimToken,
                        int attemptCount) {
        public Claim {
            Objects.requireNonNull(id, "事件 ID 不能为空");
            Objects.requireNonNull(listingId, "商品 ID 不能为空");
            Objects.requireNonNull(eventType, "事件类型不能为空");
            Objects.requireNonNull(payload, "payload 不能为空");
            Objects.requireNonNull(createdAt, "创建时间不能为空");
            Objects.requireNonNull(ownerId, "owner 不能为空");
            Objects.requireNonNull(claimToken, "claim token 不能为空");
            if (aggregateVersion <= 0 || schemaVersion != ProductSnapshotEvent.CURRENT_SCHEMA_VERSION
                || attemptCount <= 0) {
                throw new IllegalArgumentException("商品事件领取参数无效");
            }
        }
    }
}
