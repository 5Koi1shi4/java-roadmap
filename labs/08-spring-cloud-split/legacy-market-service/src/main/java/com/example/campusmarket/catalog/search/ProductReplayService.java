package com.example.campusmarket.catalog.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** 本机运维使用的保留商品事件 replay；不暴露 HTTP 入口。 */
@Service
@Profile("!test")
public final class ProductReplayService {
    private static final long CLAIM_LEASE_MICROS = 60_000_000L;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final ProductEventPublisher publisher;
    private final String owner = "product-replay-" + UUID.randomUUID();
    private final ReentrantLock runLock = new ReentrantLock();

    public ProductReplayService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                ObjectMapper mapper, ProductEventPublisher publisher) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC 不能为空");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
        this.mapper = Objects.requireNonNull(mapper, "ObjectMapper 不能为空");
        this.publisher = Objects.requireNonNull(publisher, "商品事件发布器不能为空");
    }

    /**
     * 固定一次 MAX(sequence_no) 高水位，以有界批量重发保留事件。
     * 返回本次已收到 broker confirm 的事件数；投影侧以 eventId/版本幂等收敛。
     */
    public int replayOnce(int limit) {
        if (limit <= 0 || limit > 1000) {
            throw new IllegalArgumentException("replay 数量必须在 1 到 1000 之间");
        }
        if (!runLock.tryLock()) {
            return 0;
        }
        try {
            if (!publisher.brokerHealthy()) {
                return 0;
            }
            ReplayClaim claim = acquireClaim();
            if (claim == null) {
                return 0;
            }
            int published = 0;
            try {
                while (published < limit) {
                    int batchSize = Math.min(100, limit - published);
                    List<ReplayRow> rows = load(claim.nextSequenceNo(), claim.highWatermark(), batchSize);
                    if (rows.isEmpty()) {
                        finish(claim);
                        break;
                    }
                    for (ReplayRow row : rows) {
                        ProductSnapshotEvent event = decodeAndCheck(row);
                        publisher.publish(event);
                        if (advance(claim, row.sequenceNo()) == 0) {
                            throw new IllegalStateException("replay claim 已失效");
                        }
                        claim = claim.withNextSequence(row.sequenceNo() + 1);
                        published++;
                        if (claim.nextSequenceNo() > claim.highWatermark()) {
                            finish(claim);
                            return published;
                        }
                    }
                }
                return published;
            } catch (RuntimeException failure) {
                release(claim);
                throw failure;
            }
        } finally {
            runLock.unlock();
        }
    }

    private ReplayClaim acquireClaim() {
        return transactions.execute(status -> {
            ReplayRowState current = jdbc.queryForObject("""
                SELECT high_watermark,next_sequence_no,owner_id,claim_token,lease_until,status
                FROM product_replay_claim WHERE id=1 FOR UPDATE
                """, (rs, rowNum) -> new ReplayRowState(rs.getLong("high_watermark"),
                rs.getLong("next_sequence_no"), rs.getString("owner_id"), rs.getString("claim_token"),
                rs.getTimestamp("lease_until"), rs.getString("status")));
            Timestamp databaseNow = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class);
            boolean leaseActive = current.leaseUntil() != null && current.leaseUntil().after(databaseNow);
            if (leaseActive && !owner.equals(current.ownerId())) {
                return null;
            }

            boolean continuing = "RUNNING".equals(current.status()) && current.nextSequenceNo() > 0;
            long highWatermark = continuing ? current.highWatermark()
                : jdbc.queryForObject("SELECT COALESCE(MAX(sequence_no),0) FROM search_outbox", Long.class);
            long nextSequenceNo = continuing ? current.nextSequenceNo() : 1L;
            boolean continueSameOwner = continuing && leaseActive && owner.equals(current.ownerId())
                && current.claimToken() != null;
            String token = continueSameOwner ? current.claimToken() : UUID.randomUUID().toString();
            int changed = jdbc.update("""
                UPDATE product_replay_claim
                SET high_watermark=?, next_sequence_no=?, owner_id=?, claim_token=?,
                    lease_until=TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)),
                    status='RUNNING', updated_at=CURRENT_TIMESTAMP(6)
                WHERE id=1
                """, highWatermark, nextSequenceNo, owner, token, CLAIM_LEASE_MICROS);
            if (changed != 1) {
                return null;
            }
            return new ReplayClaim(highWatermark, nextSequenceNo, owner, token);
        });
    }

    private List<ReplayRow> load(long nextSequenceNo, long highWatermark, int limit) {
        return jdbc.query("""
            SELECT sequence_no,id,listing_id,aggregate_version,event_type,payload,schema_version
            FROM search_outbox
            WHERE sequence_no>=? AND sequence_no<=?
            ORDER BY sequence_no
            LIMIT ?
            """, (rs, rowNum) -> new ReplayRow(rs.getLong("sequence_no"), rs.getString("id"),
            UUID.fromString(rs.getString("listing_id")), rs.getLong("aggregate_version"),
            rs.getString("event_type"), rs.getString("payload"), rs.getInt("schema_version")),
            nextSequenceNo, highWatermark, limit);
    }

    private int advance(ReplayClaim claim, long sequenceNo) {
        return transactions.execute(status -> jdbc.update("""
            UPDATE product_replay_claim
            SET next_sequence_no=?, lease_until=TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)),
                updated_at=CURRENT_TIMESTAMP(6)
            WHERE id=1 AND status='RUNNING' AND owner_id=? AND claim_token=?
              AND lease_until > CURRENT_TIMESTAMP(6) AND next_sequence_no <= ?
            """, sequenceNo + 1, CLAIM_LEASE_MICROS, claim.ownerId(), claim.claimToken(), sequenceNo));
    }

    private void finish(ReplayClaim claim) {
        transactions.executeWithoutResult(status -> jdbc.update("""
            UPDATE product_replay_claim
            SET status='IDLE', owner_id=NULL, claim_token=NULL, lease_until=NULL,
                next_sequence_no=high_watermark+1, updated_at=CURRENT_TIMESTAMP(6)
            WHERE id=1 AND status='RUNNING' AND owner_id=? AND claim_token=?
              AND lease_until > CURRENT_TIMESTAMP(6)
            """, claim.ownerId(), claim.claimToken()));
    }

    private void release(ReplayClaim claim) {
        transactions.executeWithoutResult(status -> jdbc.update("""
            UPDATE product_replay_claim
            SET owner_id=NULL, claim_token=NULL, lease_until=NULL, updated_at=CURRENT_TIMESTAMP(6)
            WHERE id=1 AND status='RUNNING' AND owner_id=? AND claim_token=?
            """, claim.ownerId(), claim.claimToken()));
    }

    private ProductSnapshotEvent decodeAndCheck(ReplayRow row) {
        final ProductSnapshotEvent event;
        try {
            event = mapper.readValue(row.payload(), ProductSnapshotEvent.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException("保留商品事件 payload 无效", e);
        }
        if (!row.id().equals(event.eventId().toString())
            || !row.listingId().equals(event.listingId())
            || row.aggregateVersion() != event.aggregateVersion()
            || !row.eventType().equals(event.eventType())
            || row.schemaVersion() != event.schemaVersion()) {
            throw new IllegalArgumentException("保留商品事件与源 outbox 元数据不一致");
        }
        return event;
    }

    private record ReplayRow(long sequenceNo, String id, UUID listingId, long aggregateVersion,
                             String eventType, String payload, int schemaVersion) { }

    private record ReplayRowState(long highWatermark, long nextSequenceNo, String ownerId,
                                  String claimToken, Timestamp leaseUntil, String status) { }

    private record ReplayClaim(long highWatermark, long nextSequenceNo, String ownerId,
                               String claimToken) {
        private ReplayClaim withNextSequence(long value) {
            return new ReplayClaim(highWatermark, value, ownerId, claimToken);
        }
    }
}
