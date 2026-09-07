package com.example.campusmarket.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 校园交易平台统一指标门面。标签只接受固定枚举，调用者不能把业务 ID 放入指标。
 */
@Component
public final class CampusMetrics {
    private static final Set<String> RESULTS = Set.of("SUCCESS", "FAILURE", "REJECTED", "TIMEOUT", "DUPLICATE", "UNKNOWN");
    private static final Set<String> ORDER_STATES = Set.of("PENDING_PAYMENT", "AWAITING_HANDOFF", "AWAITING_RECEIPT",
        "AFTERSALE_WINDOW", "DISPUTED", "REFUNDING_CANCEL", "CANCELLED", "REFUNDED", "SETTLED");
    private static final Set<String> WARRANTY_STATES = Set.of("OPEN", "SELLER_RESPONDED", "UNDER_REVIEW", "RESOLVED", "REJECTED", "ESCALATED");
    private static final Set<String> OBLIGATION_STATES = Set.of("AWAITING_FUNDING", "FUNDED", "OVERDUE", "SETTLED");

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> gauges = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    public CampusMetrics(MeterRegistry registry) {
        this.registry = registry;
        registerGauge("campus.market.warranty.unfunded", "status", "AWAITING_FUNDING");
        registerGauge("campus.market.seller.restricted", "status", "ACTIVE");
        registerGauge("campus.market.warranty.seller_response.timeout", "result", "TIMEOUT");
        registerGauge("campus.market.warranty.funding.timeout", "result", "TIMEOUT");
        registerGauge("campus.market.admin.sla.timeout", "result", "TIMEOUT");
        registerGauge("campus.market.admin.hard_deadline", "result", "ESCALATED");
    }

    public void recordReview(String result) { counter("campus.market.review.total", "result", result).increment(); }
    public void recordVerification(String result) { counter("campus.market.identity.verification.total", "result", result).increment(); }
    public void recordListingPublished(String result) { counter("campus.market.listing.publish.total", "result", result).increment(); }
    public void recordSearch(String result) { counter("campus.market.search.total", "result", result).increment(); }
    public void recordPaymentCallback(String result) { counter("campus.market.payment.callback.total", "result", result).increment(); }
    public void recordRefund(String result) { counter("campus.market.refund.total", "result", result).increment(); }
    public void recordOutbox(String status) { counter("campus.market.outbox.total", "status", status).increment(); }
    public void recordInbox(String status) { counter("campus.market.inbox.total", "status", status).increment(); }
    public void recordStorage(String result) { counter("campus.market.storage.operation.total", "result", result).increment(); }
    public void recordSellerResponseTimeout() { counter("campus.market.warranty.seller_response.timeout.total", "result", "TIMEOUT").increment(); }
    public void recordFundingTimeout() { counter("campus.market.warranty.funding.timeout.total", "result", "TIMEOUT").increment(); }
    public void recordAdminSlaTimeout() { counter("campus.market.admin.sla.timeout.total", "result", "TIMEOUT").increment(); }
    public void recordHardDeadlineEscalation() { counter("campus.market.admin.hard_deadline.total", "result", "ESCALATED").increment(); }
    public void recordLeaseTakeover(String subsystem) {
        counter("campus.market.lease.takeover.total", "subsystem",
            fixed(subsystem, Set.of("OUTBOX", "INBOX", "STORAGE", "DEADLINE"), "UNKNOWN")).increment();
    }
    public void recordRetry(String subsystem, String result) {
        String fixedSubsystem = fixed(subsystem, Set.of("OUTBOX", "INBOX", "STORAGE", "PAYMENT", "REFUND"), "UNKNOWN");
        counter("campus.market.retry.total." + fixedSubsystem, "result", result).increment();
    }

    public void recordOrderState(String state) {
        counter("campus.market.order.state", "state", fixed(state, ORDER_STATES, "UNKNOWN")).increment();
    }
    public void recordWarrantyState(String state) {
        counter("campus.market.warranty.case.state", "state", fixed(state, WARRANTY_STATES, "UNKNOWN")).increment();
    }
    public void recordObligationState(String state) {
        counter("campus.market.seller.obligation.state", "state", fixed(state, OBLIGATION_STATES, "UNKNOWN")).increment();
    }

    public void recordOperationDuration(String operation, java.time.Duration duration) {
        if (duration == null || duration.isNegative()) throw new IllegalArgumentException("耗时不能为空或为负数");
        String fixedOperation = fixed(operation, Set.of("UPLOAD", "READ", "DELETE", "SEARCH", "PAYMENT", "REFUND"), "UNKNOWN");
        timers.computeIfAbsent(fixedOperation, key -> Timer.builder("campus.market.operation.duration")
            .tag("operation", key).register(registry)).record(duration);
    }

    public void setWarrantyUnfunded(int value) { gauge("campus.market.warranty.unfunded", "status", "AWAITING_FUNDING").set(nonNegative(value)); }
    public void setRestrictedSellers(int value) { gauge("campus.market.seller.restricted", "status", "ACTIVE").set(nonNegative(value)); }
    public void setSellerResponseTimeouts(int value) { gauge("campus.market.warranty.seller_response.timeout", "result", "TIMEOUT").set(nonNegative(value)); }
    public void setFundingTimeouts(int value) { gauge("campus.market.warranty.funding.timeout", "result", "TIMEOUT").set(nonNegative(value)); }
    public void setAdminSlaTimeouts(int value) { gauge("campus.market.admin.sla.timeout", "result", "TIMEOUT").set(nonNegative(value)); }
    public void setEscalatedHardDeadlines(int value) { gauge("campus.market.admin.hard_deadline", "result", "ESCALATED").set(nonNegative(value)); }

    public MeterRegistry registry() { return registry; }

    private Counter counter(String name, String key, String value) {
        Set<String> allowed = Set.of("NEW", "PUBLISHING", "PUBLISHED", "FAILED", "PROCESSING", "COMPLETED",
            "REQUESTED", "SUCCEEDED", "REFUNDING", "REJECTED", "TIMEOUT", "DUPLICATE", "UNKNOWN", "SUCCESS",
            "FAILURE", "SETTLED", "OPEN", "SELLER_RESPONDED", "UNDER_REVIEW", "RESOLVED", "ESCALATED",
            "AWAITING_FUNDING", "FUNDED", "OVERDUE");
        String fixed = allowed.contains(value) ? value : "UNKNOWN";
        return counters.computeIfAbsent(name + '|' + key + '|' + fixed,
            ignored -> Counter.builder(name).tag(key, fixed).register(registry));
    }

    private AtomicInteger gauge(String name, String key, String value) {
        return gauges.computeIfAbsent(name + '|' + key + '|' + value,
            ignored -> new AtomicInteger());
    }

    private void registerGauge(String name, String key, String value) {
        AtomicInteger valueHolder = gauge(name, key, value);
        Gauge.builder(name, valueHolder, AtomicInteger::get).tag(key, value).register(registry);
    }

    private static int nonNegative(int value) { return Math.max(value, 0); }
    private static String fixed(String value, Set<String> allowed, String fallback) {
        return value != null && allowed.contains(value) ? value : fallback;
    }
}
