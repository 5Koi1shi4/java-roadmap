package com.example.campusmarket.unit.observability;

import com.example.campusmarket.observability.SafeAuditEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeAuditEventTest {
    @Test
    void rejectsSensitiveSecurityAndStorageFields() {
        for (String field : new String[]{
            "token", "verificationCode", "signature", "objectKey", "presignedUrl", "email", "stackTrace"}) {
            assertThatThrownBy(() -> SafeAuditEvent.success(
                    UUID.randomUUID(), "REVIEW_CREATED", "TRADE_ORDER", UUID.randomUUID(),
                    Map.of(field, "sensitive-value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("审计字段");
        }
        assertThatThrownBy(() -> SafeAuditEvent.success(
                UUID.randomUUID(), "ACCESS", "ORDER", UUID.randomUUID(),
                Map.of("detail", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsSafeLowCardinalityDetailsAndDefensivelyCopiesThem() {
        Map<String, Object> source = new java.util.HashMap<>();
        source.put("reason", "CONFLICT");
        SafeAuditEvent event = SafeAuditEvent.failure(
            UUID.randomUUID(), "REVIEW_REJECTED", "TRADE_ORDER", UUID.randomUUID(),
            "CONFLICT", source);

        source.put("leak", "must-not-appear");

        assertThat(event.details()).containsEntry("reason", "CONFLICT")
            .doesNotContainKey("leak");
        assertThat(event.correlationId()).isNotNull();
    }
}
