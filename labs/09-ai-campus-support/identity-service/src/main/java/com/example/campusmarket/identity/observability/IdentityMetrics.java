package com.example.campusmarket.identity.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 身份服务专属指标门面，只暴露固定低基数标签。 */
public final class IdentityMetrics {
    private static final Set<String> VERIFICATION_RESULTS = Set.of(
        "SUCCESS", "FAILURE", "RATE_LIMITED", "SENT", "VERIFIED");

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public IdentityMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "指标注册表不能为空");
    }

    public void recordVerification(String result) {
        String fixed = VERIFICATION_RESULTS.contains(result) ? result : "FAILURE";
        counters.computeIfAbsent(fixed, key -> Counter.builder("campus.market.identity.verification.total")
            .tag("result", key).register(registry)).increment();
    }

    public void recordAuthentication(String result) {
        String fixed = Set.of("SUCCESS", "FAILURE", "REJECTED").contains(result) ? result : "FAILURE";
        counters.computeIfAbsent("authentication|" + fixed, key -> Counter.builder("campus.market.identity.authentication.total")
            .tag("result", fixed).register(registry)).increment();
    }

    public MeterRegistry registry() {
        return registry;
    }
}
