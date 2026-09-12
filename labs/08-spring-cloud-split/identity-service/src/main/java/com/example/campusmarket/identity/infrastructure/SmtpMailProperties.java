package com.example.campusmarket.identity.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

/** 生产 SMTP 必需配置；该配置仅由 production 配置类注册。 */
@ConfigurationProperties("campus.market.mail.smtp")
public record SmtpMailProperties(String host, Integer port, String username, String password, String from) {
    public SmtpMailProperties {
        requireText(host, "SMTP host");
        Objects.requireNonNull(port, "SMTP port is required");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("SMTP port is out of range");
        }
        requireText(from, "SMTP sender address");
        if ((username == null) != (password == null)) {
            throw new IllegalArgumentException("SMTP username and password must be configured together");
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }
}
