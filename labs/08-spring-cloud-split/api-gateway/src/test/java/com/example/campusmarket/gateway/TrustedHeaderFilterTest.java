package com.example.campusmarket.gateway;

import com.example.campusmarket.gateway.filter.TrustedHeaderFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证客户端无法伪造 Gateway 或下游使用的身份与来源 Header。 */
class TrustedHeaderFilterTest {

    @Test
    void removesSpoofableHeadersAndCreatesNewCorrelationId() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/listings")
            .header("Authorization", "Bearer keep-this-token")
            .header("X-User-Id", "attacker")
            .header("X-User-Roles", "ROLE_ADMIN")
            .header("X-Authenticated-User", "attacker")
            .header("X-Internal-Role", "ROLE_ADMIN")
            .header("X-Correlation-Id", "chosen-by-client")
            .header("Forwarded", "for=198.51.100.1")
            .header("X-Forwarded-For", "198.51.100.1")
            .header("X-Real-IP", "198.51.100.1")
            .build();

        ServerHttpRequest filtered = new TrustedHeaderFilter().filter(request);

        assertThat(filtered.getHeaders()).doesNotContainKeys(
            "X-User-Id", "X-User-Roles", "X-Authenticated-User", "X-Internal-Role",
            "Forwarded", "X-Forwarded-For", "X-Real-IP");
        assertThat(filtered.getHeaders().getFirst("Authorization"))
            .isEqualTo("Bearer keep-this-token");
        String correlationId = filtered.getHeaders().getFirst("X-Correlation-Id");
        assertThat(correlationId)
            .isNotEqualTo("chosen-by-client")
            .isNotNull()
            .matches("[0-9a-f-]{36}");
        assertThat(UUID.fromString(correlationId)).isNotNull();
    }
}
