package com.example.campusmarket.api;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;

/** 统一安全错误响应工具；安全链仍由 identity 模块装配，避免重复 SecurityFilterChain Bean。 */
public final class SecurityConfiguration {
    private static final SecureRandom RANDOM = new SecureRandom();

    private SecurityConfiguration() { }

    public static String correlationId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return UUID.nameUUIDFromBytes(bytes).toString();
    }

    public static void writeJsonError(HttpServletResponse response, HttpStatus status,
                                      String code, String message) throws IOException {
        String correlationId = correlationId();
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json; charset=UTF-8");
        String json = "{\"code\":\"" + escape(code) + "\",\"message\":\"" + escape(message)
            + "\",\"correlationId\":\"" + correlationId + "\"}";
        response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
