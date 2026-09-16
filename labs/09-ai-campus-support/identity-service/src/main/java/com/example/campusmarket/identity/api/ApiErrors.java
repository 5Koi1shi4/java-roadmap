package com.example.campusmarket.identity.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;

/** 错误响应构造器，显式固定 UTF-8。 */
public final class ApiErrors {
    public static final MediaType JSON = MediaType.parseMediaType("application/json; charset=UTF-8");
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiErrors() {
    }

    public static ResponseEntity<ApiError> entity(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).contentType(JSON)
            .body(new ApiError(code, message, correlationId()));
    }

    public static ResponseEntity<byte[]> bytes(HttpStatus status, String code, String message) {
        ApiError error = new ApiError(code, message, correlationId());
        String body = "{\"code\":\"" + escape(error.code()) + "\",\"message\":\"" + escape(error.message())
            + "\",\"correlationId\":\"" + escape(error.correlationId()) + "\"}";
        return ResponseEntity.status(status).contentType(JSON).body(body.getBytes(StandardCharsets.UTF_8));
    }

    public static String correlationId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return UUID.nameUUIDFromBytes(bytes).toString();
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
