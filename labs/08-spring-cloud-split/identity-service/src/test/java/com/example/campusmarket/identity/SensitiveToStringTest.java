package com.example.campusmarket.identity;

import com.example.campusmarket.identity.api.AuthController;
import com.example.campusmarket.identity.application.AuthService;
import com.example.campusmarket.identity.infrastructure.RedisVerificationCodeStore;
import com.example.campusmarket.identity.infrastructure.SmtpMailProperties;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 敏感请求/配置/令牌数据不能由默认 record toString 泄露。 */
class SensitiveToStringTest {
    @Test
    void redactsSensitiveConfigurationAndVerificationValues() {
        assertThat(new SmtpMailProperties("smtp.example.edu.cn", 587, "mailer", "smtp-password",
            "noreply@example.edu.cn").toString())
            .doesNotContain("smtp-password", "noreply@example.edu.cn");
        assertThat(new RedisVerificationCodeStore.IssuedCode("123456", "verification-hmac").toString())
            .doesNotContain("123456", "verification-hmac");
    }

    @Test
    void redactsSensitiveAuthRequestAndResponseValues() {
        assertThat(new AuthController.AuthRequest("student@stu.example.edu.cn", "password-value", "123456",
            null, "REGISTER", null, null, null).toString())
            .doesNotContain("password-value", "123456");
        assertThat(new AuthController.LoginResponse("access-token-value", "Bearer", 900,
            UUID.randomUUID().toString(), Set.of("ROLE_USER")).toString())
            .doesNotContain("access-token-value");
        assertThat(new AuthService.LoginResult(UUID.randomUUID(), "access-token-value",
            java.time.Duration.ofMinutes(15), Set.of("ROLE_USER")).toString())
            .doesNotContain("access-token-value");
    }
}
