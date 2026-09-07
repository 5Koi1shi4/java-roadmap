package com.example.campusmarket.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** consumed_event 的原子领取与完成记录。 */
@Repository
public class InboxRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final PlatformTransactionManager transactionManager;

    public InboxRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC 不能为空");
        this.transactionTemplate = null;
        this.transactionManager = null;
    }

    @Autowired
    public InboxRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC 不能为空");
        this.transactionTemplate = new TransactionTemplate(Objects.requireNonNull(transactionManager,
            "事务管理器不能为空"));
        this.transactionManager = transactionManager;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Optional<Claim> claim(String consumerName, UUID eventId, Duration lease) {
        requireConsumer(consumerName);
        Objects.requireNonNull(eventId, "eventId 不能为空");
        long micros = durationMicros(lease);
        String owner = consumerName + "-" + UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO consumed_event (id,consumer_name,event_id,status,owner_id,claim_token,lease_until,attempt_count,created_at)
            VALUES (?,?,?,'PROCESSING',?,?,TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)),0,CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE id=consumed_event.id
            """, UUID.randomUUID().toString(), consumerName, eventId.toString(), owner, token, micros);

        UUID exhaustedManualId = UUID.nameUUIDFromBytes((consumerName + ":" + eventId)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int exhausted = jdbc.update("""
            UPDATE consumed_event
            SET status='FAILED', owner_id=NULL, claim_token=NULL, lease_until=NULL
            WHERE consumer_name=? AND event_id=? AND status='PROCESSING'
              AND lease_until <= CURRENT_TIMESTAMP(6) AND attempt_count >= 3
            """, consumerName, eventId.toString());
        if (exhausted == 1) {
            jdbc.update("""
                INSERT INTO manual_failure (id,source_type,source_id,consumer_name,failure_class,payload,status,created_at)
                SELECT ?, 'INBOX', event_id, consumer_name, 'EXHAUSTED',
                       JSON_OBJECT('eventId',event_id,'consumerName',consumer_name), 'NEW', CURRENT_TIMESTAMP(6)
                FROM consumed_event
                WHERE consumer_name=? AND event_id=? AND status='FAILED'
                ON DUPLICATE KEY UPDATE id=manual_failure.id
                """, exhaustedManualId.toString(), consumerName, eventId.toString());
        }

        int changed = jdbc.update("""
            UPDATE consumed_event
            SET status='PROCESSING', owner_id=?, claim_token=?,
                lease_until=TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)),
                attempt_count=attempt_count+1
            WHERE consumer_name=? AND event_id=? AND status='PROCESSING'
              AND (((lease_until <= CURRENT_TIMESTAMP(6)) AND attempt_count < 3) OR (owner_id=? AND claim_token=?))
            """, owner, token, micros, consumerName, eventId.toString(), owner, token);
        if (changed == 1) {
            return Optional.of(new Claim(consumerName, eventId, owner, token, false, false));
        }
        String status = jdbc.queryForObject("SELECT status FROM consumed_event WHERE consumer_name=? AND event_id=?",
            String.class, consumerName, eventId.toString());
        return "COMPLETED".equals(status) || "FAILED".equals(status)
            ? Optional.of(new Claim(consumerName, eventId, null, null, "COMPLETED".equals(status), "FAILED".equals(status)))
            : Optional.empty();
    }

    @Transactional
    public int complete(String consumerName, UUID eventId, String owner, String claimToken) {
        requireConsumer(consumerName);
        Objects.requireNonNull(eventId, "eventId 不能为空");
        Objects.requireNonNull(owner, "owner 不能为空");
        Objects.requireNonNull(claimToken, "claim token 不能为空");
        return jdbc.update("""
            UPDATE consumed_event
            SET status='COMPLETED', completed_at=CURRENT_TIMESTAMP(6), owner_id=NULL, claim_token=NULL, lease_until=NULL
            WHERE consumer_name=? AND event_id=? AND status='PROCESSING' AND owner_id=? AND claim_token=?
            """, consumerName, eventId.toString(), owner, claimToken);
    }

    /** 业务可恢复失败时立即释放当前租约，让 Rabbit requeue 的下一次 delivery 可重新领取。 */
    @Transactional
    public int releaseForRetry(String consumerName, UUID eventId, String owner, String claimToken) {
        requireConsumer(consumerName);
        Objects.requireNonNull(eventId, "eventId 不能为空");
        Objects.requireNonNull(owner, "owner 不能为空");
        Objects.requireNonNull(claimToken, "claim token 不能为空");
        return jdbc.update("""
            UPDATE consumed_event
            SET lease_until=CURRENT_TIMESTAMP(6)
            WHERE consumer_name=? AND event_id=? AND status='PROCESSING' AND owner_id=? AND claim_token=?
              AND attempt_count < 3
            """, consumerName, eventId.toString(), owner, claimToken);
    }

    public boolean isFailed(String consumerName, UUID eventId) {
        requireConsumer(consumerName);
        Objects.requireNonNull(eventId, "eventId 不能为空");
        String status = jdbc.queryForObject("SELECT status FROM consumed_event WHERE consumer_name=? AND event_id=?",
            String.class, consumerName, eventId.toString());
        return "FAILED".equals(status);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public int markFailed(String consumerName, UUID eventId, String owner, String claimToken) {
        requireConsumer(consumerName);
        Objects.requireNonNull(eventId, "eventId 不能为空");
        Objects.requireNonNull(owner, "owner 不能为空");
        Objects.requireNonNull(claimToken, "claim token 不能为空");
        return markFailedInternal(consumerName, eventId, owner, claimToken);
    }

    private int markFailedInternal(String consumerName, UUID eventId, String owner, String claimToken) {
        UUID manualId = UUID.nameUUIDFromBytes((consumerName + ":" + eventId)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int changed = jdbc.update("""
            UPDATE consumed_event SET status='FAILED', owner_id=NULL, claim_token=NULL, lease_until=NULL
            WHERE consumer_name=? AND event_id=? AND status='PROCESSING' AND owner_id=? AND claim_token=?
            """, consumerName, eventId.toString(), owner, claimToken);
        if (changed == 1) {
            jdbc.update("""
                INSERT INTO manual_failure (id,source_type,source_id,consumer_name,failure_class,payload,status,created_at)
                VALUES (?,?,?,?,'PERMANENT',JSON_OBJECT('eventId',?,'consumerName',?),'NEW',CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE id=manual_failure.id
                """, manualId.toString(), "INBOX", eventId.toString(), consumerName,
                eventId.toString(), consumerName);
        }
        return changed;
    }

    /**
     * 执行业务变更和 COMPLETED 标记的同一事务。返回 true 才允许消息适配器 ACK；异常会回滚两者。
     */
    public boolean process(String consumerName, UUID eventId, Duration lease, Consumer<Claim> businessWork) {
        DeliveryResult result = processForDelivery(consumerName, eventId, lease, businessWork);
        if (result == DeliveryResult.PERMANENT_FAILED) {
            throw new IllegalArgumentException("业务处理不可恢复失败");
        }
        return result == DeliveryResult.COMPLETED;
    }

    /**
     * 为真实 Rabbit delivery 返回带 fencing 语义的结果。STALE 绝不能依据数据库 FAILED 状态
     * 替旧 owner ACK 或再次发布人工通知。
     */
    public DeliveryResult processForDelivery(String consumerName, UUID eventId, Duration lease,
                                             Consumer<Claim> businessWork) {
        Objects.requireNonNull(businessWork, "业务处理器不能为空");
        Optional<Claim> claimed = claim(consumerName, eventId, lease);
        if (claimed.isEmpty()) return DeliveryResult.NOT_CLAIMED;
        Claim claim = claimed.get();
        if (claim.alreadyCompleted()) return DeliveryResult.COMPLETED;
        if (claim.failed()) return DeliveryResult.FAILED;
        if (transactionTemplate == null) {
            throw new IllegalStateException("process 需要事务管理器");
        }
        try {
            Boolean committed = transactionTemplate.execute(status -> {
                businessWork.accept(claim);
                if (complete(consumerName, eventId, claim.ownerId(), claim.claimToken()) != 1) {
                    throw new IllegalStateException("Inbox 完成时 token 已失效");
                }
                return true;
            });
            return Boolean.TRUE.equals(committed) ? DeliveryResult.COMPLETED : DeliveryResult.NOT_CLAIMED;
        } catch (EventBusinessHandler.UnsupportedEventException unsupported) {
            // The message was delivered to the wrong family queue. Persist a
            // manual-failure record before the broker delivery is rejected;
            // otherwise a requeue=false NACK leaves PROCESSING forever.
            TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
            requiresNew.setPropagationBehaviorName("PROPAGATION_REQUIRES_NEW");
            Integer changed = requiresNew.execute(status -> markFailedInternal(consumerName, eventId,
                claim.ownerId(), claim.claimToken()));
            return Integer.valueOf(1).equals(changed) ? DeliveryResult.PERMANENT_FAILED : DeliveryResult.STALE;
        } catch (IllegalArgumentException permanentFailure) {
            TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
            requiresNew.setPropagationBehaviorName("PROPAGATION_REQUIRES_NEW");
            Integer changed = requiresNew.execute(status -> markFailedInternal(consumerName, eventId,
                claim.ownerId(), claim.claimToken()));
            return Integer.valueOf(1).equals(changed) ? DeliveryResult.PERMANENT_FAILED : DeliveryResult.STALE;
        } catch (RuntimeException retryableFailure) {
            releaseForRetry(consumerName, eventId, claim.ownerId(), claim.claimToken());
            throw retryableFailure;
        }
    }

    public enum DeliveryResult {
        COMPLETED, FAILED, NOT_CLAIMED, PERMANENT_FAILED, STALE
    }

    private static long durationMicros(Duration duration) {
        Objects.requireNonNull(duration, "租约不能为空");
        if (duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("租约必须为正数");
        try {
            long micros = Math.addExact(Math.multiplyExact(duration.getSeconds(), 1_000_000L), duration.getNano() / 1_000L);
            if (micros <= 0) throw new IllegalArgumentException("租约精度不足一微秒");
            return micros;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("租约溢出", e);
        }
    }

    private static void requireConsumer(String value) {
        Objects.requireNonNull(value, "consumerName 不能为空");
        if (value.isBlank() || value.length() > 62) throw new IllegalArgumentException("consumerName 无效");
    }

    public record Claim(String consumerName, UUID eventId, String ownerId, String claimToken,
                        boolean alreadyCompleted, boolean failed) {
    }
}
