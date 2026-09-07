package com.example.campusmarket.api;

/** 所有公开 API 错误的稳定协议。 */
public record ApiError(String code, String message, String correlationId) { }
