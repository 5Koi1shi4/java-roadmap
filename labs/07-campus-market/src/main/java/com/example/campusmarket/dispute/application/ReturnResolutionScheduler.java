package com.example.campusmarket.dispute.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 退款回调/对账后的小批量收敛器；重复执行只命中唯一库存业务键。 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "campus.market.dispute.return-reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
public final class ReturnResolutionScheduler {
    private final JdbcTemplate jdbc;
    private final ReturnResolutionService resolutions;

    public ReturnResolutionScheduler(JdbcTemplate jdbc, ReturnResolutionService resolutions) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.resolutions = Objects.requireNonNull(resolutions, "退回解析服务不能为空");
    }

    @Scheduled(initialDelayString = "${campus.market.dispute.return-reconciliation.initial-delay-ms:1000}", fixedDelayString = "${campus.market.dispute.return-reconciliation.fixed-delay-ms:1000}")
    public void dispatch() { runOnce(50); }

    public int runOnce(int limit) {
        if (limit <= 0 || limit > 1000) throw new IllegalArgumentException("退回解析批量大小必须在1到1000之间");
        List<UUID> ids = jdbc.query("SELECT DISTINCT f.id FROM refund_order f JOIN return_case r ON (r.refund_id=f.id OR (r.refund_id IS NULL AND r.order_id=f.order_id AND (f.idempotency_key=CONCAT('dispute-return-',r.dispute_case_id) OR f.idempotency_key=CONCAT('dispute-hard-refund-',r.dispute_case_id)))) "
                + "WHERE f.status='SUCCEEDED' AND (r.refund_id IS NULL OR r.refund_status<>'SUCCEEDED' OR (r.resolution_type='RETURN_AND_REFUND' AND r.quarantined_at IS NULL)) ORDER BY f.updated_at,f.id LIMIT ?",
            (rs, n) -> UUID.fromString(rs.getString(1)), limit);
        int completed = 0;
        for (UUID id : ids) {
            resolutions.reconcileSuccessfulRefund(id);
            completed++;
        }
        return completed;
    }
}
