package com.example.campusmarket.payment.infrastructure;

import com.example.campusmarket.payment.application.PaymentGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 仅供 local/test 的独立 HTTP 支付提供方模拟器，不接触真实资金。 */
@RestController
@Profile({"local", "test"})
@ConditionalOnProperty(name = "campus.market.payment.simulation-enabled", havingValue = "true")
@RequestMapping(path = {"/simulated-provider", "/api/simulated-provider"}, produces = "application/json; charset=UTF-8")
public class SimulatedPaymentProviderController {
    private final ObjectMapper objectMapper;
    private final String provider;
    private final Map<String, PaymentEntry> payments = new ConcurrentHashMap<>();
    private final Map<String, RefundEntry> refunds = new ConcurrentHashMap<>();
    private final Map<String, PaymentEntry> paymentsByKey = new ConcurrentHashMap<>();
    private final Map<String, RefundEntry> refundsByKey = new ConcurrentHashMap<>();

    public SimulatedPaymentProviderController(ObjectMapper objectMapper,
                                               @Value("${campus.market.payment.provider:simulated}") String provider,
                                               @Value("${campus.market.payment.simulation-enabled:false}") boolean enabled,
                                               org.springframework.core.env.Environment environment) {
        this.objectMapper = objectMapper;
        this.provider = provider;
        boolean allowed = environment.matchesProfiles("local", "test");
        if (enabled && !allowed) throw new IllegalStateException("模拟支付控制端仅允许 local/test Profile");
    }

    @PostMapping(path = "/payments", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PaymentResponse createPayment(@RequestBody PaymentRequest request) {
        if (request.orderId() == null || request.orderId().isBlank() || request.amountFen() <= 0
            || request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("支付请求无效");
        }
        String ref = "sim-pay-" + UUID.nameUUIDFromBytes(request.idempotencyKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        PaymentEntry entry = paymentsByKey.compute(request.idempotencyKey(), (ignored, prior) -> {
            if (prior != null && (!prior.orderId().equals(request.orderId()) || prior.amountFen() != request.amountFen())) {
                throw new IdempotencyConflictException();
            }
            return prior == null ? new PaymentEntry(request.orderId(), request.amountFen(), "PENDING") : prior;
        });
        payments.putIfAbsent(ref, entry);
        return new PaymentResponse(provider, ref, entry.status(), entry.amountFen());
    }

    @GetMapping("/payments/{reference}")
    public PaymentResponse queryPayment(@PathVariable String reference) {
        PaymentEntry entry = payments.get(reference);
        if (entry == null) return new PaymentResponse(provider, reference, "UNKNOWN", 0);
        return new PaymentResponse(provider, reference, entry.status(), entry.amountFen());
    }

    @GetMapping("/payments/by-key/{idempotencyKey}")
    public PaymentResponse queryPaymentByKey(@PathVariable String idempotencyKey) {
        PaymentEntry entry = paymentsByKey.get(idempotencyKey);
        if (entry == null) return new PaymentResponse(provider, "unknown", "UNKNOWN", 0);
        String ref = "sim-pay-" + UUID.nameUUIDFromBytes(idempotencyKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new PaymentResponse(provider, ref, entry.status(), entry.amountFen());
    }

    @PostMapping("/payments/{reference}/{status}")
    public PaymentResponse setPaymentStatus(@PathVariable String reference, @PathVariable String status) {
        validateStatus(status);
        PaymentEntry old = payments.get(reference);
        if (old == null) return new PaymentResponse(provider, reference, "UNKNOWN", 0);
        PaymentEntry updated = new PaymentEntry(old.orderId(), old.amountFen(), status);
        payments.put(reference, updated);
        return new PaymentResponse(provider, reference, status, old.amountFen());
    }

    @PostMapping(path = "/refunds", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RefundResponse createRefund(@RequestBody RefundRequest request) {
        if (request.orderId() == null || request.orderId().isBlank() || request.paymentProviderReference() == null
            || request.paymentProviderReference().isBlank() || request.amountFen() <= 0
            || request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("退款请求无效");
        }
        String ref = "sim-refund-" + UUID.nameUUIDFromBytes(request.idempotencyKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        RefundEntry entry = refundsByKey.compute(request.idempotencyKey(), (ignored, prior) -> {
            if (prior != null && (prior.amountFen() != request.amountFen()
                || !prior.paymentReference().equals(request.paymentProviderReference()))) throw new IdempotencyConflictException();
            return prior == null ? new RefundEntry(request.amountFen(), "PENDING", request.paymentProviderReference()) : prior;
        });
        refunds.putIfAbsent(ref, entry);
        return new RefundResponse(provider, ref, entry.status(), entry.amountFen());
    }

    @GetMapping("/refunds/{reference}")
    public RefundResponse queryRefund(@PathVariable String reference) {
        RefundEntry entry = refunds.get(reference);
        if (entry == null) return new RefundResponse(provider, reference, "UNKNOWN", 0);
        return new RefundResponse(provider, reference, entry.status(), entry.amountFen());
    }

    @GetMapping("/refunds/by-key/{idempotencyKey}")
    public RefundResponse queryRefundByKey(@PathVariable String idempotencyKey) {
        RefundEntry entry = refundsByKey.get(idempotencyKey);
        if (entry == null) return new RefundResponse(provider, "unknown", "UNKNOWN", 0);
        String ref = "sim-refund-" + UUID.nameUUIDFromBytes(idempotencyKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new RefundResponse(provider, ref, entry.status(), entry.amountFen());
    }

    @PostMapping("/refunds/{reference}/{status}")
    public RefundResponse setRefundStatus(@PathVariable String reference, @PathVariable String status) {
        validateStatus(status);
        RefundEntry old = refunds.get(reference);
        if (old == null) return new RefundResponse(provider, reference, "UNKNOWN", 0);
        refunds.put(reference, new RefundEntry(old.amountFen(), status));
        return new RefundResponse(provider, reference, status, old.amountFen());
    }

    /** 测试控制接口：生成带签名的回调所需字段，但回调投递由测试/客户端执行。 */
    @PostMapping(path = "/callbacks", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CallbackResponse callback(@RequestBody CallbackRequest request) {
        if (!"PAYMENT".equals(request.type()) && !"REFUND".equals(request.type())) throw new IllegalArgumentException("回调类型无效");
        validateStatus(request.status());
        String eventId = request.providerEventId() == null ? UUID.randomUUID().toString() : request.providerEventId();
        return new CallbackResponse(provider, eventId, request.type(), request.providerReference(),
            request.amountFen(), request.status(), Instant.now().getEpochSecond());
    }

    record PaymentRequest(String orderId, long amountFen, String idempotencyKey) {}
    record PaymentResponse(String provider, String providerReference, String status, long amountFen) {}
    record RefundRequest(String orderId, String paymentProviderReference, long amountFen, String idempotencyKey) {}
    record RefundResponse(String provider, String providerReference, String status, long amountFen) {}
    record CallbackRequest(String type, String providerReference, long amountFen, String status, String providerEventId) {}
    record CallbackResponse(String provider, String providerEventId, String type, String providerReference,
                            long amountFen, String status, long timestamp) {}
    private record PaymentEntry(String orderId, long amountFen, String status) {}
    private record RefundEntry(long amountFen, String status, String paymentReference) {
        RefundEntry(long amountFen, String status) { this(amountFen, status, ""); }
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<byte[]> idempotencyConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.parseMediaType("application/json; charset=UTF-8"))
            .body("{\"error\":\"幂等键请求不一致\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<byte[]> invalidProviderRequest() {
        return ResponseEntity.badRequest().contentType(MediaType.parseMediaType("application/json; charset=UTF-8"))
            .body("{\"error\":\"请求参数无效\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<byte[]> unreadableProviderRequest() {
        return ResponseEntity.badRequest().contentType(MediaType.parseMediaType("application/json; charset=UTF-8"))
            .body("{\"error\":\"请求参数无效\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public static class IdempotencyConflictException extends RuntimeException { }

    private static void validateStatus(String status) {
        if (!("PENDING".equals(status) || "SUCCEEDED".equals(status) || "FAILED".equals(status) || "UNKNOWN".equals(status))) throw new IllegalArgumentException("状态无效");
    }
}
