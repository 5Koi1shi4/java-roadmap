package com.example.campusmarket.identity.infrastructure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Redis 连接和命令等待必须由配置设定有限上界。 */
class RedisTimeoutConfigurationTest {
    @Test
    void configuresBoundedConnectCommandAndShutdownTimeouts() throws IOException {
        String yaml;
        try (InputStream resource = getClass().getResourceAsStream("/application.yml")) {
            yaml = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(yaml).contains("connect-timeout: 2s")
            .contains("timeout: 2s")
            .contains("shutdown-timeout: 100ms");
    }
}
