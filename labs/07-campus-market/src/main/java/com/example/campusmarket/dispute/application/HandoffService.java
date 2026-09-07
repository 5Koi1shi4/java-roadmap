package com.example.campusmarket.dispute.application;

import com.example.campusmarket.order.application.IdempotentCommandService;
import com.example.campusmarket.api.ApiErrors;
import org.springframework.http.HttpStatus;
import com.example.campusmarket.order.application.OrderLifecycleService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** 交付和收货用例门面；争议裁决留给 Task10。 */
@Service
@Profile("!test")
public final class HandoffService {
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
        return new Result(!body.contains("\"error\""), response);
    }

    public record Result(boolean success, byte[] responseUtf8) {}
}
