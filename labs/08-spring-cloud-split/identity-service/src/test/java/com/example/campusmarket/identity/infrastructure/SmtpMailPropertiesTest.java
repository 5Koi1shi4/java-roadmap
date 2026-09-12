package com.example.campusmarket.identity.infrastructure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class SmtpMailPropertiesTest {
    @Test
    void rejectsMissingHostOrSenderAddress() {
        assertThatThrownBy(() -> new SmtpMailProperties(null, 587, null, null, "noreply@example.edu.cn"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SmtpMailProperties("smtp.example.edu.cn", 587, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresPortToBeProvidedByProductionEnvironment() throws IOException {
        String yaml;
        try (InputStream resource = getClass().getResourceAsStream("/application.yml")) {
            yaml = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(yaml).contains("port: ${CAMPUS_MARKET_SMTP_PORT:}")
            .doesNotContain("port: ${CAMPUS_MARKET_SMTP_PORT:587}");
    }

    @Test
    void treatsBlankCredentialsAsAbsentAndRequiresPairing() {
        assertThatThrownBy(() -> new SmtpMailProperties("smtp.example.edu.cn", 587,
            " ", "secret", "noreply@example.edu.cn"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SmtpMailProperties("smtp.example.edu.cn", 587,
            "mailer", " ", "noreply@example.edu.cn"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enablesSmtpAuthenticationOnlyForNonBlankCredentialPair() {
        JavaMailSenderImpl sender = (JavaMailSenderImpl) new SmtpMailConfiguration()
            .identityJavaMailSender(new SmtpMailProperties("smtp.example.edu.cn", 587, " ", " ",
                "noreply@example.edu.cn"));
        assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.auth")).isEqualTo("false");
    }
}
