package com.example.campusmarket.unit.identity;

import com.example.campusmarket.identity.application.EmailVerificationService;
import com.example.campusmarket.identity.application.VerificationMailSender;
import com.example.campusmarket.identity.infrastructure.RedisVerificationCodeStore;
import com.example.campusmarket.identity.observability.IdentityMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailVerificationMetricsTest {
    @Test
    void issueRecordsSentAtProductionMailBoundary() {
        RedisVerificationCodeStore store = mock(RedisVerificationCodeStore.class);
        when(store.issue("buyer@stu.example.edu.cn", "REGISTER", "127.0.0.1", "device-1"))
            .thenReturn(new RedisVerificationCodeStore.IssuedCode("123456", "hmac"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IdentityMetrics metrics = new IdentityMetrics(registry);
        EmailVerificationService service = new EmailVerificationService(mock(JdbcTemplate.class), store,
            mock(VerificationMailSender.class), Set.of("stu.example.edu.cn"), metrics);

        assertThat(service.issue("buyer@stu.example.edu.cn", "127.0.0.1", "device-1", "REGISTER"))
            .isEqualTo("123456");
        assertThat(registry.get("campus.market.identity.verification.total")
            .tag("result", "SENT").counter().count()).isEqualTo(1.0);
    }
}
