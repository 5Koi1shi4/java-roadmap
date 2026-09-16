package com.example.campusmarket.testsupport;

import java.net.http.HttpResponse;
import java.util.Objects;

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
        String[] segments = contentType.split(";", -1);
        String mediaType = segments[0].trim();
        String charset = null;
        for (int i = 1; i < segments.length; i++) {
            String parameter = segments[i].trim();
            int separator = parameter.indexOf('=');
            if (separator < 0) {
                continue;
            }
            String name = parameter.substring(0, separator).trim();
            if ("charset".equalsIgnoreCase(name)) {
                if (charset != null) {
                    throw invalidContentType(contentType);
                }
                charset = unquote(parameter.substring(separator + 1).trim());
            }
        }
        if (!"application/json".equalsIgnoreCase(mediaType)
                || !"UTF-8".equalsIgnoreCase(charset)) {
            throw invalidContentType(contentType);
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static AssertionError invalidContentType(String contentType) {
        return new AssertionError("Content-Type 必须为 application/json; charset=UTF-8，实际为 " + contentType);
    }
}
