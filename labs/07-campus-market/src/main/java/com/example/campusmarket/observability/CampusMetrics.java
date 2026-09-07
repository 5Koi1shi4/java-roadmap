package com.example.campusmarket.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 校园交易平台统一指标门面。标签只接受固定枚举，调用者不能把业务 ID 放入指标。
 */
@Component
public final class CampusMetrics {
    private static final Set<String> RESULTS = Set.of("SUCCESS", "FAILURE", "REJECTED", "TIMEOUT", "DUPLICATE", "UNKNOWN",
        "SENT", "VERIFIED", "RATE_LIMITED", "RECEIVED", "CREATED", "PENDING", "SUCCEEDED", "FAILED", "CANCELLED");
    private static final Set<String> ORDER_STATES = Set.of("PENDING_PAYMENT", "AWAITING_HANDOFF", "AWAITING_RECEIPT",
        "AFTERSALE_WINDOW", "DISPUTED", "REFUNDING_CANCEL", "CANCELLED", "REFUNDED", "SETTLED");
    private static final Set<String> WARRANTY_STATES = Set.of("OPEN", "SELLER_RESPONDED", "UNDER_REVIEW", "RESOLVED", "REJECTED", "ESCALATED");
    private static final Set<String> OBLIGATION_STATES = Set.of("AWAITING_FUNDING", "PARTIALLY_FUNDED", "FUNDED", "CANCELLED");
    private static final Set<String> OUTBOX_STATES = Set.of("NEW", "PUBLISHING", "PUBLISHED", "FAILED");
    private static final Set<String> INBOX_STATES = Set.of("PROCESSING", "COMPLETED", "FAILED");

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> snapshotGauges = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    public CampusMetrics(MeterRegistry registry) {
        this.registry = registry;
        registerGauge("campus.market.warranty.unfunded", "status", "AWAITING_FUNDING");
        registerGauge("campus.market.seller.restricted", "status", "ACTIVE");
        registerGauge("campus.market.warranty.seller_response.timeout", "result", "TIMEOUT");
        registerGauge("campus.market.warranty.funding.timeout", "result", "TIMEOUT");
        registerGauge("campus.market.dispute.seller_response.timeout", "result", "TIMEOUT");
        registerGauge("campus.market.dispute.admin.sla.timeout", "result", "TIMEOUT");
        registerGauge("campus.market.dispute.admin.hard_deadline", "result", "ESCALATED");
        registerGauge("campus.market.admin.sla.timeout", "result", "TIMEOUT");
        registerGauge("campus.market.admin.hard_deadline", "result", "ESCALATED");
    }

    public void recordReview(String result) { counter("campus.market.review.total", "result", result, RESULTS).increment(); }
    public void recordVerification(String result) { counter("campus.market.identity.verification.total", "result", result, RESULTS).increment(); }
    public void recordListingPublished(String result) { counter("campus.market.listing.publish.total", "result", result, RESULTS).increment(); }
    public void recordSearch(String result) { counter("campus.market.search.total", "result", result, RESULTS).increment(); }
    public void recordPaymentCallback(String result) { counter("campus.market.payment.callback.total", "result", result, RESULTS).increment(); }
    public void recordRefund(String result) { counter("campus.market.refund.total", "result", result, RESULTS).increment(); }
    public void recordOutbox(String status) { counter("campus.market.outbox.total", "status", status, OUTBOX_STATES).increment(); }
    public void recordInbox(String status) { counter("campus.market.inbox.total", "status", status, INBOX_STATES).increment(); }
    public void recordStorage(String result) { counter("campus.market.storage.operation.total", "result", result, RESULTS).increment(); }
    public void recordSellerResponseTimeout() { counter("campus.market.warranty.seller_response.timeout.total", "result", "TIMEOUT", RESULTS).increment(); }
    public void recordFundingTimeout() { counter("campus.market.warranty.funding.timeout.total", "result", "TIMEOUT", RESULTS).increment(); }
    public void recordAdminSlaTimeout() { counter("campus.market.admin.sla.timeout.total", "result", "TIMEOUT", RESULTS).increment(); }
    public void recordHardDeadlineEscalation() { counter("campus.market.admin.hard_deadline.total", "result", "ESCALATED", RESULTS).increment(); }
    public void recordLeaseTakeover(String subsystem) {
        counter("campus.market.lease.takeover.total", "subsystem", subsystem,
            Set.of("OUTBOX", "INBOX", "STORAGE", "DEADLINE")).increment();
    }
    public void recordRetry(String subsystem, String result) {
        String fixedSubsystem = fixed(subsystem, Set.of("OUTBOX", "INBOX", "STORAGE", "PAYMENT", "REFUND"), "UNKNOWN");
        counter("campus.market.retry.total." + fixedSubsystem, "result", result, RESULTS).increment();
    }

    public void recordOrderState(String state) {
        counter("campus.market.order.state", "state", state, ORDER_STATES).increment();
    }
    public void recordWarrantyState(String state) {
        counter("campus.market.warranty.case.state", "state", state, WARRANTY_STATES).increment();
    }
    public void recordObligationState(String state) {
        counter("campus.market.seller.obligation.state", "state", state, OBLIGATION_STATES).increment();
    }

    public void recordOperationDuration(String operation, java.time.Duration duration) {
        if (duration == null || duration.isNegative()) throw new IllegalArgumentException("耗时不能为空或为负数");
        String fixedOperation = fixed(operation, Set.of("UPLOAD", "READ", "DELETE", "SEARCH", "PAYMENT", "REFUND"), "UNKNOWN");
        timers.computeIfAbsent(fixedOperation, key -> Timer.builder("campus.market.operation.duration")
            .tag("operation", key).register(registry)).record(duration);
    }

    public void setWarrantyUnfunded(int value) { snapshot("campus.market.warranty.unfunded", "status", "AWAITING_FUNDING").set(nonNegative(value)); }
    public void setRestrictedSellers(int value) { snapshot("campus.market.seller.restricted", "status", "ACTIVE").set(nonNegative(value)); }
    public void setSellerResponseTimeouts(int value) { snapshot("campus.market.warranty.seller_response.timeout", "result", "TIMEOUT").set(nonNegative(value)); }
    public void setFundingTimeouts(int value) { snapshot("campus.market.warranty.funding.timeout", "result", "TIMEOUT").set(nonNegative(value)); }
    public void setDisputeSellerResponseTimeouts(int value) { snapshot("campus.market.dispute.seller_response.timeout", "result", "TIMEOUT").set(nonNegative(value)); }
    public void setDisputeAdminSlaTimeouts(int value) { snapshot("campus.market.dispute.admin.sla.timeout", "result", "TIMEOUT").set(nonNegative(value)); }
    public void setDisputeHardDeadlineEscalations(int value) { snapshot("campus.market.dispute.admin.hard_deadline", "result", "ESCALATED").set(nonNegative(value)); }
    public void setAdminSlaTimeouts(int value) { snapshot("campus.market.admin.sla.timeout", "result", "TIMEOUT").set(nonNegative(value)); }
    public void setEscalatedHardDeadlines(int value) { snapshot("campus.market.admin.hard_deadline", "result", "ESCALATED").set(nonNegative(value)); }

    public MeterRegistry registry() { return registry; }

    private Counter counter(String name, String key, String value, Set<String> allowed) {
        String fixed = fixed(value, allowed, "UNKNOWN");
        return counters.computeIfAbsent(name + '|' + key + '|' + fixed,
            ignored -> Counter.builder(name).tag(key, fixed).register(registry));
    }

    AtomicLong snapshot(String name, String key, String value) {
        if (key == null || value == null) throw new IllegalArgumentException("快照标签不能为空");
        String mapKey = name + '|' + key + '|' + value;
        AtomicLong holder = snapshotGauges.computeIfAbsent(mapKey, ignored -> new AtomicLong());
        if (registry.find(name).tag(key, value).gauge() == null) {
            Gauge.builder(name, holder, AtomicLong::get).tag(key, value).register(registry);
        }
        return holder;
    }

    AtomicLong snapshot(String name) {
        AtomicLong holder = snapshotGauges.computeIfAbsent(name, ignored -> new AtomicLong());
        if (registry.find(name).gauge() == null) {
            Gauge.builder(name, holder, AtomicLong::get).register(registry);
        }
        return holder;
    }

    private void registerGauge(String name, String key, String value) {
        snapshot(name, key, value);
    }

    private static int nonNegative(int value) { return Math.max(value, 0); }
    private static String fixed(String value, Set<String> allowed, String fallback) {
        return value != null && allowed.contains(value) ? value : fallback;
    }
}
