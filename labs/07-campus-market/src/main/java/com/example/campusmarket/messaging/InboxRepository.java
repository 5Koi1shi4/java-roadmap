package com.example.campusmarket.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
@ConditionalOnBean(JdbcTemplate.class)
public class InboxRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    public InboxRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC 不能为空");
        this.transactionTemplate = null;
    }

    @Autowired
    public InboxRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC 不能为空");
        this.transactionTemplate = new TransactionTemplate(Objects.requireNonNull(transactionManager,
            "事务管理器不能为空"));
    }

    @Transactional
    public Optional<Claim> claim(String consumerName, UUID eventId, Duration lease) {
        requireConsumer(consumerName);
        Objects.requireNonNull(eventId, "eventId 不能为空");
        long micros = durationMicros(lease);
        String owner = consumerName + "-" + UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO consumed_event (id,consumer_name,event_id,status,owner_id,claim_token,lease_until,attempt_count,created_at)
            VALUES (?,?,?,'PROCESSING',?,?,TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)),0,CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE id=id
            """, UUID.randomUUID().toString(), consumerName, eventId.toString(), owner, token, micros);

        int changed = jdbc.update("""
            UPDATE consumed_event
            SET status='PROCESSING', owner_id=?, claim_token=?,
                lease_until=TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)),
                attempt_count=attempt_count+1
            WHERE consumer_name=? AND event_id=? AND status='PROCESSING'
              AND ((lease_until < CURRENT_TIMESTAMP(6)) OR (owner_id=? AND claim_token=?))
            """, owner, token, micros, consumerName, eventId.toString(), owner, token);
        if (changed == 1) {
            return Optional.of(new Claim(consumerName, eventId, owner, token, false));
        }
        String status = jdbc.queryForObject("SELECT status FROM consumed_event WHERE consumer_name=? AND event_id=?",
            String.class, consumerName, eventId.toString());
        return "COMPLETED".equals(status)
            ? Optional.of(new Claim(consumerName, eventId, null, null, true))
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

    @Transactional
    public int markFailed(String consumerName, UUID eventId, String owner, String claimToken) {
        requireConsumer(consumerName);
        Objects.requireNonNull(eventId, "eventId 不能为空");
        Objects.requireNonNull(owner, "owner 不能为空");
        Objects.requireNonNull(claimToken, "claim token 不能为空");
        return jdbc.update("""
            UPDATE consumed_event SET status='FAILED', owner_id=NULL, claim_token=NULL, lease_until=NULL
            WHERE consumer_name=? AND event_id=? AND status='PROCESSING' AND owner_id=? AND claim_token=?
            """, consumerName, eventId.toString(), owner, claimToken);
    }

    /**
     * 执行业务变更和 COMPLETED 标记的同一事务。返回 true 才允许消息适配器 ACK；异常会回滚两者。
     */
    public boolean process(String consumerName, UUID eventId, Duration lease, Consumer<Claim> businessWork) {
        Objects.requireNonNull(businessWork, "业务处理器不能为空");
        Optional<Claim> claimed = claim(consumerName, eventId, lease);
        if (claimed.isEmpty()) return false;
        Claim claim = claimed.get();
        if (claim.alreadyCompleted()) return true;
        if (transactionTemplate == null) {
            throw new IllegalStateException("process 需要事务管理器");
        }
        Boolean committed = transactionTemplate.execute(status -> {
            businessWork.accept(claim);
            if (complete(consumerName, eventId, claim.ownerId(), claim.claimToken()) != 1) {
                throw new IllegalStateException("Inbox 完成时 token 已失效");
            }
            return true;
        });
        return Boolean.TRUE.equals(committed);
    }

    private static long durationMicros(Duration duration) {
        Objects.requireNonNull(duration, "租约不能为空");
        if (duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("租约必须为正数");
        try {
            return Math.addExact(Math.multiplyExact(duration.getSeconds(), 1_000_000L), duration.getNano() / 1_000L);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("租约溢出", e);
        }
    }

    private static void requireConsumer(String value) {
        Objects.requireNonNull(value, "consumerName 不能为空");
        if (value.isBlank() || value.length() > 100) throw new IllegalArgumentException("consumerName 无效");
    }

    public record Claim(String consumerName, UUID eventId, String ownerId, String claimToken,
                        boolean alreadyCompleted) {
    }
}
