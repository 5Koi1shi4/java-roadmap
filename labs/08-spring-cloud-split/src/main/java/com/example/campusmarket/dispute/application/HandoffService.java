package com.example.campusmarket.dispute.application;

import com.example.campusmarket.order.application.IdempotentCommandService;
import com.example.campusmarket.api.ApiErrors;
import org.springframework.http.HttpStatus;
import com.example.campusmarket.order.application.OrderLifecycleService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 交付和收货用例门面；争议裁决留给 Task10。 */
@Service
@Profile("!test")
public final class HandoffService {
    private static final Set<String> API_ERROR_CODES = Set.of("INVALID_REQUEST", "UNAUTHENTICATED", "FORBIDDEN",
        "RESOURCE_NOT_FOUND", "CONFLICT", "BUSINESS_RULE_VIOLATION", "RATE_LIMITED", "DEPENDENCY_UNAVAILABLE",
        "INTERNAL_ERROR", "REQUEST_FAILED");
    private final OrderLifecycleService lifecycle;
    private final IdempotentCommandService commands;

    public HandoffService(OrderLifecycleService lifecycle, IdempotentCommandService commands) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "订单生命周期服务不能为空");
        this.commands = Objects.requireNonNull(commands, "幂等命令服务不能为空");
    }

    public Result handoff(UUID orderId, UUID sellerId, String idempotencyKey, String note) {
        String safeNote = note == null ? "" : note;
        byte[] request = ("HANDOFF|" + orderId + "|" + safeNote).getBytes(StandardCharsets.UTF_8);
        byte[] response = commands.executeLifecycle(sellerId, idempotencyKey, "HANDOFF", orderId, request, orderId,
            () -> lifecycle.handoff(orderId, sellerId, safeNote)
                ? success("AWAITING_RECEIPT") : conflict("交付截止或订单状态已变化"));
        return parse(response);
    }

    public Result confirmReceipt(UUID orderId, UUID buyerId, String idempotencyKey) {
        byte[] request = ("RECEIPT|" + orderId).getBytes(StandardCharsets.UTF_8);
        byte[] response = commands.executeLifecycle(buyerId, idempotencyKey, "RECEIPT", orderId, request, orderId,
            () -> lifecycle.confirmReceipt(orderId, buyerId)
                ? success("AFTERSALE_WINDOW") : conflict("收货截止或订单状态已变化"));
        return parse(response);
    }

    private static byte[] success(String status) { return ("{\"status\":\"" + status + "\"}").getBytes(StandardCharsets.UTF_8); }
    private static byte[] conflict(String message) { return ApiErrors.body(HttpStatus.CONFLICT, message); }

    private static Result parse(byte[] response) {
        String body = new String(response, StandardCharsets.UTF_8);
        int codeStart = body.indexOf("\"code\"");
        if (codeStart >= 0) {
            int valueStart = body.indexOf('"', body.indexOf(':', codeStart) + 1);
            int valueEnd = valueStart < 0 ? -1 : body.indexOf('"', valueStart + 1);
            if (valueEnd > valueStart && API_ERROR_CODES.contains(body.substring(valueStart + 1, valueEnd))) {
                return new Result(false, response);
            }
        }
        return new Result(true, response);
    }

    public record Result(boolean success, byte[] responseUtf8) {}
}
