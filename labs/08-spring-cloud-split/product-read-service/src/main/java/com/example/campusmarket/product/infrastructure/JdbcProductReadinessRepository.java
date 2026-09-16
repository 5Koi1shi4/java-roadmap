package com.example.campusmarket.product.infrastructure;

import com.example.campusmarket.product.event.ProductReplayCompleteEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;

/**
 * 持久化读侧投影的启动屏障。数据库、Rabbit 和 ES 可用不代表历史投影完整，
 * 只有同一条消费队列提交 replay 完成屏障后才允许进入 READY。
 */
@Repository
public class JdbcProductReadinessRepository {
    private static final String PENDING_STATUSES = "('NEW','PUBLISHING','FAILED')";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate inspectionTransaction;
    private final TransactionTemplate independentTransaction;

    public JdbcProductReadinessRepository(JdbcTemplate jdbc,
                                          PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        Objects.requireNonNull(transactionManager, "事务管理器不能为空");
        this.inspectionTransaction = new TransactionTemplate(transactionManager);
        this.independentTransaction = new TransactionTemplate(transactionManager);
        this.independentTransaction.setPropagationBehavior(
            TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 第一条有效快照到达后离开冷启动 WAITING；不能覆盖 BLOCKED 或 READY。 */
    public void markSnapshotReceived() {
        jdbc.update("""
            UPDATE product_projection_readiness
            SET state='CATCHING_UP',updated_at=CURRENT_TIMESTAMP(6)
            WHERE id=1 AND state='WAITING'
            """);
    }

    /**
     * 在快照和 index outbox 同一事务中消费完成屏障。
     * index 高水位取屏障事务看到的本地 outbox 最大 sequence_no，
     * 因此只需清空该高水位以前的未完成待办。
     */
    public void markReplayComplete(ProductReplayCompleteEvent marker) {
        Objects.requireNonNull(marker, "replay 完成事件不能为空");
        long indexHighWatermark = indexHighWatermark();
        long pending = pendingIndexCount(indexHighWatermark);
        jdbc.update("""
            UPDATE product_projection_readiness
            SET state=CASE WHEN ?=0 THEN 'READY' ELSE 'CATCHING_UP' END,
                replay_id=?,source_high_watermark=?,index_high_watermark=?,
                updated_at=CURRENT_TIMESTAMP(6)
            WHERE id=1 AND state<>'BLOCKED'
            """, pending, marker.replayId().toString(), marker.sourceHighWatermark(),
            indexHighWatermark);
    }

    /**
     * 协议错误或人工失败路径必须独立提交 BLOCKED，避免外层消费事务回滚掉这个状态。
     * 读侧没有公开运维端点，恢复由新的 bounded replay 代际完成。
     */
    public void markBlocked() {
        independentTransaction.executeWithoutResult(status -> jdbc.update("""
            UPDATE product_projection_readiness
            SET state='BLOCKED',updated_at=CURRENT_TIMESTAMP(6)
            WHERE id=1
            """));
    }

    /**
     * 读取并在历史 index 待办清空后提升 READY。提升在同一短事务内完成，
     * 使 health 探针不会在 worker 刚提交时读到半完成状态。
     */
    public ReadinessStatus inspect() {
        ReadinessStatus result = inspectionTransaction.execute(status -> {
            ReadinessRow row = jdbc.queryForObject("""
                SELECT state,replay_id,source_high_watermark,index_high_watermark
                FROM product_projection_readiness
                WHERE id=1
                FOR UPDATE
                """, (rs, rowNum) -> new ReadinessRow(
                rs.getString("state"),
                rs.getString("replay_id"),
                rs.getLong("source_high_watermark"),
                rs.getLong("index_high_watermark")));
            if (row == null) {
                throw new IllegalStateException("投影 readiness checkpoint 缺失");
            }
            long pending = pendingIndexCount(row.indexHighWatermark());
            String stateValue = row.state();
            UUID replayId = parseReplayId(row.replayId());
            if ("CATCHING_UP".equals(stateValue) && replayId != null && pending == 0) {
                jdbc.update("""
                    UPDATE product_projection_readiness
                    SET state='READY',updated_at=CURRENT_TIMESTAMP(6)
                    WHERE id=1 AND state='CATCHING_UP' AND replay_id=?
                    """, replayId.toString());
                stateValue = "READY";
            }
            return new ReadinessStatus(stateValue, replayId, row.sourceHighWatermark(),
                row.indexHighWatermark(), pending);
        });
        return Objects.requireNonNull(result, "投影 readiness 查询结果为空");
    }

    private long indexHighWatermark() {
        Long value = jdbc.queryForObject(
            "SELECT COALESCE(MAX(sequence_no),0) FROM product_index_outbox", Long.class);
        return value == null ? 0 : value;
    }

    private long pendingIndexCount(long highWatermark) {
        Long value = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM product_index_outbox
            WHERE sequence_no <= ? AND status IN """ + PENDING_STATUSES, Long.class,
            highWatermark);
        return value == null ? 0 : value;
    }

    private static UUID parseReplayId(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("投影 replay ID 无效", invalid);
        }
    }

    private record ReadinessRow(String state, String replayId, long sourceHighWatermark,
                                long indexHighWatermark) {
    }

    public record ReadinessStatus(String state, UUID replayId, long sourceHighWatermark,
                                  long indexHighWatermark, long pendingIndexCount) {
        public ReadinessStatus {
            Objects.requireNonNull(state, "投影 readiness 状态不能为空");
            if (sourceHighWatermark < 0 || indexHighWatermark < 0 || pendingIndexCount < 0) {
                throw new IllegalArgumentException("投影 readiness 高水位或积压无效");
            }
        }
    }
}
