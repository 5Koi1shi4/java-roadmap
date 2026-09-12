package com.example.campusmarket.identity.api;

/** 身份服务公开错误协议。 */
public record ApiError(String code, String message, String correlationId) {
}
