package com.example.seckill.application;

public record IdempotentOrderResult(int httpStatus, String responseBody) {
}
