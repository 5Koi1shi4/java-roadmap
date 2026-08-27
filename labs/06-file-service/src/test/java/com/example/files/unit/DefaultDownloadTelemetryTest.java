package com.example.files.unit;

import com.example.files.application.access.DefaultDownloadTelemetry;
import com.example.files.application.audit.CorrelationId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultDownloadTelemetryTest {
    @Test
    void exportsOnlyFixedLowCardinalityFailureReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DefaultDownloadTelemetry telemetry = new DefaultDownloadTelemetry(registry);

        telemetry.failed(UUID.randomUUID(), CorrelationId.random(), "attacker-controlled-token-or-path");

        assertThat(telemetry.failureCount()).isEqualTo(1);
        assertThat(registry.get("file.download.failures").tag("reason", "OTHER").counter().count())
            .isEqualTo(1);
        assertThat(registry.getMeters()).hasSize(1);
    }
}
