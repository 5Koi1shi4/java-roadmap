package com.example.campusmarket.testsupport;

import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 HTTP 响应断言助手，供集成测试复用。
 *
 * <p>HTTP JSON 必须显式声明 UTF-8，即 {@code Content-Type: application/json; charset=UTF-8}。
 */
public final class HttpAssertions {

    private static final String CONTENT_TYPE_HEADER = "Content-Type";

    private HttpAssertions() {
    }

    /**
     * 断言响应是 JSON 且显式使用 UTF-8 字符集。
     *
     * @param response 待检查响应，必须非空
     */
    public static void assertJsonUtf8(HttpResponse<?> response) {
        Objects.requireNonNull(response, "response");

        String contentType = response.headers().firstValue(CONTENT_TYPE_HEADER).orElse("");
        String normalizedContentType = contentType.toLowerCase(Locale.ROOT);
        assertThat(normalizedContentType)
                .as("Content-Type 必须声明 JSON")
                .contains("application/json");
        assertThat(normalizedContentType)
                .as("Content-Type 必须显式声明 UTF-8")
                .contains("charset=utf-8");
    }
}
