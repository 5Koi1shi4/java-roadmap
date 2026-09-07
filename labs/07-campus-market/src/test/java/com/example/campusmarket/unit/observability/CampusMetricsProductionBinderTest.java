package com.example.campusmarket.unit.observability;

import com.example.campusmarket.observability.CampusMetrics;
import com.example.campusmarket.observability.CampusMetricsProductionBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CampusMetricsProductionBinderTest {
    @Test
    void refreshesDatabaseSnapshotThroughProductionBinder() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Number.class), any())).thenReturn(3L);
        when(jdbc.queryForObject(anyString(), eq(Number.class))).thenReturn(3L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CampusMetrics metrics = new CampusMetrics(registry);

        new CampusMetricsProductionBinder(jdbc, metrics).refreshSnapshot();

        assertThat(registry.get("campus.market.order.state.count").tag("state", "SETTLED").gauge().value())
            .isEqualTo(3.0);
        assertThat(registry.get("campus.market.warranty.unfunded").tag("status", "AWAITING_FUNDING").gauge().value())
            .isEqualTo(3.0);
        assertThat(registry.get("campus.market.warranty.funding.timeout").tag("result", "TIMEOUT").gauge().value())
            .isEqualTo(3.0);
        assertThat(registry.get("campus.market.dispute.admin.hard_deadline").tag("result", "ESCALATED").gauge().value())
            .isEqualTo(3.0);
        assertThat(registry.get("campus.market.outbox.backlog").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("campus.market.refund.pending.oldest.delay").gauge().value()).isEqualTo(3.0);
    }
}
