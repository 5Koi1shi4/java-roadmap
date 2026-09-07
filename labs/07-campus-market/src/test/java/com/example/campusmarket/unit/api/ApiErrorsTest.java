package com.example.campusmarket.unit.api;

import com.example.campusmarket.api.ApiErrors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorsTest {
    @Test
    void emitsFixedUtf8ErrorShapeWithServerGeneratedCorrelationId() {
        var response = ApiErrors.bytes(HttpStatus.NOT_FOUND, "资源不存在");
        String body = new String(response.getBody(), StandardCharsets.UTF_8);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType().toString()).containsIgnoringCase("charset=UTF-8");
        assertThat(body).contains("\"code\":\"RESOURCE_NOT_FOUND\"")
            .contains("\"message\":\"资源不存在\"");
        String correlationId = body.replaceFirst(".*\\\"correlationId\\\":\\\"([^\\\"]+)\\\".*", "$1");
        assertThat(UUID.fromString(correlationId)).isNotNull();
    }
}
