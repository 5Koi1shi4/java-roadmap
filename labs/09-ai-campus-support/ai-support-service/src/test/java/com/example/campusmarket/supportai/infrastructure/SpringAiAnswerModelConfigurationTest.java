package com.example.campusmarket.supportai.infrastructure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 固定模型超时必须同时落到 Spring AI 底层 OpenAI HTTP 客户端。 */
class SpringAiAnswerModelConfigurationTest {
    @Test
    void openAiHttpTimeoutIsFixedAtThreeSecondsAndNotEnvironmentConfigurable()
            throws IOException {
        String yaml;
        try (var stream = getClass().getResourceAsStream("/application.yml")) {
            yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(yaml).contains("      timeout: 3s");
        assertThat(yaml).doesNotContain("CAMPUS_MARKET_AI_TIMEOUT");
    }
}
