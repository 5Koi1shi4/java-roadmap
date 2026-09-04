package com.example.campusmarket.payment.infrastructure;

import com.example.campusmarket.payment.application.PaymentGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

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
        if (request.amountFen() <= 0 || request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("支付请求无效");
        }
        String ref = "sim-pay-" + UUID.nameUUIDFromBytes(request.idempotencyKey().getBytes());
        payments.putIfAbsent(ref, new PaymentEntry(request.orderId(), request.amountFen(), "PENDING"));
        return new PaymentResponse(provider, ref, payments.get(ref).status(), request.amountFen());
    }

    @GetMapping("/payments/{reference}")
    public PaymentResponse queryPayment(@PathVariable String reference) {
        PaymentEntry entry = payments.get(reference);
        if (entry == null) return new PaymentResponse(provider, reference, "UNKNOWN", 0);
        return new PaymentResponse(provider, reference, entry.status(), entry.amountFen());
    }

    @PostMapping("/payments/{reference}/{status}")
    public PaymentResponse setPaymentStatus(@PathVariable String reference, @PathVariable String status) {
        PaymentEntry old = payments.get(reference);
        if (old == null) return new PaymentResponse(provider, reference, "UNKNOWN", 0);
        PaymentEntry updated = new PaymentEntry(old.orderId(), old.amountFen(), status);
        payments.put(reference, updated);
        return new PaymentResponse(provider, reference, status, old.amountFen());
    }

    @PostMapping(path = "/refunds", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RefundResponse createRefund(@RequestBody RefundRequest request) {
        if (request.amountFen() <= 0 || request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("退款请求无效");
        }
        String ref = "sim-refund-" + UUID.nameUUIDFromBytes(request.idempotencyKey().getBytes());
        refunds.putIfAbsent(ref, new RefundEntry(request.amountFen(), "PENDING"));
        return new RefundResponse(provider, ref, refunds.get(ref).status(), request.amountFen());
    }

    @GetMapping("/refunds/{reference}")
    public RefundResponse queryRefund(@PathVariable String reference) {
        RefundEntry entry = refunds.get(reference);
        if (entry == null) return new RefundResponse(provider, reference, "UNKNOWN", 0);
        return new RefundResponse(provider, reference, entry.status(), entry.amountFen());
    }

    @PostMapping("/refunds/{reference}/{status}")
    public RefundResponse setRefundStatus(@PathVariable String reference, @PathVariable String status) {
        RefundEntry old = refunds.get(reference);
        if (old == null) return new RefundResponse(provider, reference, "UNKNOWN", 0);
        refunds.put(reference, new RefundEntry(old.amountFen(), status));
        return new RefundResponse(provider, reference, status, old.amountFen());
    }

    /** 测试控制接口：生成带签名的回调所需字段，但回调投递由测试/客户端执行。 */
    @PostMapping(path = "/callbacks", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CallbackResponse callback(@RequestBody CallbackRequest request) {
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
    private record RefundEntry(long amountFen, String status) {}
}
