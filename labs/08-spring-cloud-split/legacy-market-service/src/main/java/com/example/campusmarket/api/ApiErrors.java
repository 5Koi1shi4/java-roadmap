package com.example.campusmarket.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

/** API-wide fixed error response factory. */
public final class ApiErrors {
    private static final MediaType JSON = MediaType.parseMediaType("application/json; charset=UTF-8");
    private ApiErrors() { }

    public static ResponseEntity<byte[]> bytes(HttpStatus status, String message) {
        String code = switch (status.value()) {
            case 400 -> "INVALID_REQUEST";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 409 -> "CONFLICT";
            case 422 -> "BUSINESS_RULE_VIOLATION";
            case 429 -> "RATE_LIMITED";
            case 503 -> "DEPENDENCY_UNAVAILABLE";
            case 500 -> "INTERNAL_ERROR";
            default -> "REQUEST_FAILED";
        };
        ApiError error = new ApiError(code, message, SecurityConfiguration.correlationId());
        return ResponseEntity.status(status).contentType(JSON).body(body(error));
    }

    public static byte[] body(HttpStatus status, String message) {
        return body(new ApiError(code(status), message, SecurityConfiguration.correlationId()));
    }

    public static ResponseEntity<ApiError> entity(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(JSON)
            .body(new ApiError(code(status), message, SecurityConfiguration.correlationId()));
    }

    private static String code(HttpStatus status) {
        return switch (status.value()) {
            case 400 -> "INVALID_REQUEST"; case 401 -> "UNAUTHENTICATED"; case 403 -> "FORBIDDEN";
            case 404 -> "RESOURCE_NOT_FOUND"; case 409 -> "CONFLICT"; case 422 -> "BUSINESS_RULE_VIOLATION";
            case 429 -> "RATE_LIMITED";
            case 503 -> "DEPENDENCY_UNAVAILABLE"; case 500 -> "INTERNAL_ERROR"; default -> "REQUEST_FAILED";
        };
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
                    if (c < 0x20) escaped.append(String.format("\\u%04x", (int) c));
                    else escaped.append(c);
                }
            }
        }
        return escaped.toString();
    }

    private static byte[] body(ApiError error) {
        String body = "{\"code\":\"" + escape(error.code()) + "\",\"message\":\"" + escape(error.message())
            + "\",\"correlationId\":\"" + error.correlationId() + "\"}";
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
