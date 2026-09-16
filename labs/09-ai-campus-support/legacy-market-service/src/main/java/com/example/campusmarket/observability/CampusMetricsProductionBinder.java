package com.example.campusmarket.observability;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.Objects;

/**
 * Binds low-cardinality operational gauges to MySQL facts.  Reads are deliberately
 * aggregate-only: identifiers, email addresses and provider references never
 * become metric labels or values.
 */
@Component
@Profile("!test")
public final class CampusMetricsProductionBinder {
    private static final Set<String> ORDER_STATES = Set.of("PENDING_PAYMENT", "AWAITING_HANDOFF",
        "AWAITING_RECEIPT", "AFTERSALE_WINDOW", "DISPUTED", "REFUNDING_CANCEL", "CANCELLED", "REFUNDED", "SETTLED");
    private static final Set<String> CASE_STATES = Set.of("OPEN", "SELLER_RESPONDED", "UNDER_REVIEW", "RESOLVED", "REJECTED", "ESCALATED");
    private static final Set<String> OBLIGATION_STATES = Set.of("AWAITING_FUNDING", "PARTIALLY_FUNDED", "FUNDED", "CANCELLED");
    private static final Set<String> OUTBOX_STATES = Set.of("NEW", "PUBLISHING", "PUBLISHED", "FAILED");
    private static final Set<String> INBOX_STATES = Set.of("PROCESSING", "COMPLETED", "FAILED");
    private static final Set<String> PAYMENT_STATES = Set.of("CREATED", "PENDING", "SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN");
    private static final Set<String> CALLBACK_STATES = Set.of("RECEIVED", "PROCESSING", "COMPLETED", "FAILED");
    private static final Set<String> REFUND_STATES = Set.of("REQUESTED", "PROCESSING", "SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN");
    private final JdbcTemplate jdbc;
    private final CampusMetrics metrics;

    public CampusMetricsProductionBinder(JdbcTemplate jdbc, CampusMetrics metrics) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.metrics = Objects.requireNonNull(metrics, "指标门面不能为空");
    }

    @PostConstruct
    void initialize() { refreshSnapshot(); }

    @Scheduled(fixedDelayString = "${campus.market.metrics.refresh-ms:10000}")
    public void refreshSnapshot() {
        for (String state : ORDER_STATES) set("campus.market.order.state.count", "state", state,
            count("SELECT COUNT(*) FROM trade_order WHERE status=?", state));
        for (String state : CASE_STATES) {
            set("campus.market.dispute.case.state.count", "state", state, count("SELECT COUNT(*) FROM dispute_case WHERE status=?", state));
            set("campus.market.warranty.case.state.count", "state", state, count("SELECT COUNT(*) FROM warranty_case WHERE status=?", state));
        }
        for (String state : OBLIGATION_STATES) set("campus.market.seller.obligation.state.count", "state", state,
            count("SELECT COUNT(*) FROM seller_obligation WHERE status=?", state));

        for (String state : OUTBOX_STATES) set("campus.market.outbox.state.count", "status", state,
            count("SELECT COUNT(*) FROM integration_outbox WHERE status=?", state));
        for (String state : INBOX_STATES) set("campus.market.inbox.state.count", "status", state,
            count("SELECT COUNT(*) FROM consumed_event WHERE status=?", state));
        for (String state : OUTBOX_STATES) set("campus.market.search.outbox.state.count", "status", state,
            count("SELECT COUNT(*) FROM search_outbox WHERE status=?", state));
        for (String state : PAYMENT_STATES) set("campus.market.payment.state.count", "status", state,
            count("SELECT COUNT(*) FROM payment_order WHERE status=?", state));
        for (String state : CALLBACK_STATES) set("campus.market.payment.callback.state.count", "status", state,
            count("SELECT COUNT(*) FROM payment_callback_event WHERE status=?", state));
        for (String state : REFUND_STATES) set("campus.market.refund.state.count", "status", state,
            count("SELECT COUNT(*) FROM refund_order WHERE status=?", state));
        set("campus.market.outbox.backlog", null, null, count("SELECT COUNT(*) FROM integration_outbox WHERE status IN ('NEW','PUBLISHING','FAILED')"));
        set("campus.market.outbox.oldest.delay", null, null, delay("SELECT TIMESTAMPDIFF(MICROSECOND, MIN(created_at), CURRENT_TIMESTAMP(6))/1000000.0 FROM integration_outbox WHERE status IN ('NEW','PUBLISHING','FAILED')"));
        set("campus.market.inbox.backlog", null, null, count("SELECT COUNT(*) FROM consumed_event WHERE status IN ('PROCESSING','FAILED')"));
        set("campus.market.inbox.oldest.delay", null, null, delay("SELECT TIMESTAMPDIFF(MICROSECOND, MIN(created_at), CURRENT_TIMESTAMP(6))/1000000.0 FROM consumed_event WHERE status IN ('PROCESSING','FAILED')"));
        set("campus.market.search.outbox.backlog", null, null, count("SELECT COUNT(*) FROM search_outbox WHERE status IN ('NEW','PUBLISHING','FAILED')"));
        set("campus.market.search.outbox.oldest.delay", null, null, delay("SELECT TIMESTAMPDIFF(MICROSECOND, MIN(created_at), CURRENT_TIMESTAMP(6))/1000000.0 FROM search_outbox WHERE status IN ('NEW','PUBLISHING','FAILED')"));
        set("campus.market.refund.pending", null, null, count("SELECT COUNT(*) FROM refund_order WHERE status IN ('REQUESTED','PROCESSING')"));
        set("campus.market.refund.pending.oldest.delay", null, null, delay("SELECT TIMESTAMPDIFF(MICROSECOND, MIN(created_at), CURRENT_TIMESTAMP(6))/1000000.0 FROM refund_order WHERE status IN ('REQUESTED','PROCESSING')"));
        set("campus.market.payment.timeout", null, null, count("SELECT COUNT(*) FROM trade_order WHERE status='PENDING_PAYMENT' AND payment_deadline < CURRENT_TIMESTAMP(6)"));
        set("campus.market.order.handoff.timeout", "result", "TIMEOUT", count("SELECT COUNT(*) FROM trade_order WHERE status='AWAITING_HANDOFF' AND handoff_deadline < CURRENT_TIMESTAMP(6)"));
        set("campus.market.warranty.seller_response.timeout", "result", "TIMEOUT", count("SELECT COUNT(*) FROM warranty_case WHERE status='OPEN' AND seller_deadline < CURRENT_TIMESTAMP(6)"));
        set("campus.market.warranty.funding.timeout", "result", "TIMEOUT", count("SELECT COUNT(*) FROM seller_obligation WHERE status IN ('AWAITING_FUNDING','PARTIALLY_FUNDED') AND funding_deadline < CURRENT_TIMESTAMP(6)"));
        set("campus.market.dispute.seller_response.timeout", "result", "TIMEOUT", count("SELECT COUNT(*) FROM dispute_case WHERE status='OPEN' AND seller_deadline < CURRENT_TIMESTAMP(6)"));
        set("campus.market.dispute.admin.sla.timeout", "result", "TIMEOUT", count("SELECT COUNT(*) FROM dispute_case WHERE status IN ('UNDER_REVIEW','ESCALATED') AND admin_deadline < CURRENT_TIMESTAMP(6)"));
        set("campus.market.dispute.admin.hard_deadline", "result", "ESCALATED", count("SELECT COUNT(*) FROM dispute_case WHERE status='ESCALATED'"));
        set("campus.market.warranty.unfunded", "status", "UNFUNDED", count("SELECT COUNT(*) FROM seller_obligation WHERE status IN ('AWAITING_FUNDING','PARTIALLY_FUNDED')"));
        set("campus.market.seller.restricted", "status", "RESTRICTED", count("SELECT COUNT(DISTINCT seller_id) FROM seller_obligation WHERE restriction_status='RESTRICTED'"));
        set("campus.market.admin.sla.timeout", "result", "TIMEOUT", count("SELECT COUNT(*) FROM warranty_case WHERE status IN ('UNDER_REVIEW','ESCALATED') AND admin_deadline < CURRENT_TIMESTAMP(6)"));
        set("campus.market.admin.hard_deadline", "result", "ESCALATED", count("SELECT COUNT(*) FROM warranty_case WHERE status='ESCALATED'"));
        set("campus.market.storage.upload.open", null, null, count("SELECT COUNT(*) FROM object_upload_session WHERE status='OPEN'"));
        set("campus.market.storage.cleanup.backlog", null, null, count("SELECT COUNT(*) FROM storage_cleanup_task WHERE status IN ('PENDING','PROCESSING','FAILED')"));
        set("campus.market.search.index_cleanup.backlog", null, null, count("SELECT COUNT(*) FROM search_index_cleanup_task WHERE status IN ('BUILDING','NEW','RUNNING','FAILED')"));
        set("campus.market.search.index_cleanup.oldest.delay", null, null, delay("SELECT TIMESTAMPDIFF(MICROSECOND, MIN(created_at), CURRENT_TIMESTAMP(6))/1000000.0 FROM search_index_cleanup_task WHERE status IN ('BUILDING','NEW','RUNNING','FAILED')"));
        set("campus.market.payment.reconciliation.backlog", null, null, count("SELECT (SELECT COUNT(*) FROM payment_order WHERE status IN ('PENDING','UNKNOWN')) + (SELECT COUNT(*) FROM refund_order WHERE status IN ('REQUESTED','PROCESSING','UNKNOWN'))"));
        set("campus.market.refund.reservation.pending", null, null, count("SELECT COUNT(*) FROM payment_order WHERE reserved_refund_fen > 0"));
    }

    private void set(String name, String key, String value, double amount) {
        long safe = Math.max(0L, Math.round(amount));
        (key == null ? metrics.snapshot(name) : metrics.snapshot(name, key, value)).set(safe);
    }

    private long count(String sql, Object... args) {
        Number value = args.length == 0 ? jdbc.queryForObject(sql, Number.class) : jdbc.queryForObject(sql, Number.class, args);
        return value == null ? 0L : value.longValue();
    }

    private double delay(String sql) {
        Number value = jdbc.queryForObject(sql, Number.class);
        return value == null ? 0.0 : Math.max(0.0, value.doubleValue());
    }
}
