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

    @Test
    void appliesSensitiveChecksToLabelsAndNormalizedDetailKeys() {
        assertThatThrownBy(() -> SafeAuditEvent.success(
            UUID.randomUUID(), "email dispatch", "ORDER", UUID.randomUUID(), Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeAuditEvent.success(
            UUID.randomUUID(), "REVIEW_CREATED", "object key", UUID.randomUUID(), Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeAuditEvent.failure(
            UUID.randomUUID(), "REVIEW_REJECTED", "ORDER", UUID.randomUUID(), "Bearer token", Map.of()))
            .isInstanceOf(IllegalArgumentException.class);

        for (String key : new String[]{"object key", "object_key", "object.key", "object-key"}) {
            assertThatThrownBy(() -> SafeAuditEvent.success(
                UUID.randomUUID(), "REVIEW_CREATED", "ORDER", UUID.randomUUID(), Map.of(key, "safe")))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> SafeAuditEvent.success(
            UUID.randomUUID(), "REVIEW_CREATED", "ORDER", UUID.randomUUID(),
            Map.of("storage", "tmp/0123456789abcdef0123456789abcdef")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeAuditEvent.success(
            UUID.randomUUID(), "审计邮箱", "ORDER", UUID.randomUUID(), Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeAuditEvent.success(
            UUID.randomUUID(), "REVIEW_CREATED", "订单对象键", UUID.randomUUID(), Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeAuditEvent.success(
            UUID.randomUUID(), "REVIEW_CREATED", "ORDER", UUID.randomUUID(),
            Map.of("说明", "Bearer abc.def.ghi")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
