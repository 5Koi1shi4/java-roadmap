package com.example.campusmarket.unit.observability;

import com.example.campusmarket.observability.CampusMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CampusMetricsRetryTest {
    @Test
    void retryRemainsDistinctWhileUnknownRemainsUnknown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CampusMetrics metrics = new CampusMetrics(registry);

        metrics.recordRetry("INBOX", "RETRY");
        metrics.recordRetry("INBOX", "not-a-result");

        assertThat(registry.get("campus.market.retry.total.INBOX").tag("result", "RETRY").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("campus.market.retry.total.INBOX").tag("result", "UNKNOWN").counter().count()).isEqualTo(1.0);
    }

    @Test
    void hardDeadlineEscalationRemainsDistinctFromUnknown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CampusMetrics metrics = new CampusMetrics(registry);

        metrics.recordHardDeadlineEscalation();

        assertThat(registry.get("campus.market.admin.hard_deadline.total")
            .tag("result", "ESCALATED").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("campus.market.admin.hard_deadline.total")
            .tag("result", "UNKNOWN").counter()).isNull();
    }
}
