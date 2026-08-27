package com.example.files.api;

/** 稳定且不泄露内部细节的 HTTP 错误结构。 */
public record ApiError(String code, String message, String correlationId) {
    public ApiError {
        if (code == null || code.isBlank() || message == null || message.isBlank()
            || correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("invalid api error");
        }
    }
}
