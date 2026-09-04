package com.example.campusmarket.payment.api;

import com.example.campusmarket.payment.application.PaymentGateway;
import com.example.campusmarket.payment.application.PaymentService;
import com.example.campusmarket.payment.application.RefundService;
import com.example.campusmarket.payment.infrastructure.SimulatedPaymentGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

@RestController
@ConditionalOnBean({PaymentService.class, RefundService.class})
@RequestMapping(produces = "application/json; charset=UTF-8")
public class PaymentWebhookController {
    private final PaymentGateway gateway;
    private final PaymentService payments;
    private final RefundService refunds;

    public PaymentWebhookController(PaymentGateway gateway, PaymentService payments, RefundService refunds) {
        this.gateway = Objects.requireNonNull(gateway, "支付网关不能为空");
        this.payments = Objects.requireNonNull(payments, "支付服务不能为空");
        this.refunds = Objects.requireNonNull(refunds, "退款服务不能为空");
    }

    @PostMapping(path = "/api/payment-webhooks/{provider}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> receive(@PathVariable String provider, @RequestBody byte[] rawBody,
                                           @RequestHeader HttpHeaders headers) {
        try {
            PaymentGateway.VerifiedCallback callback = gateway.verifyAndParse(rawBody, headers);
            if (!provider.equals(callback.provider())) throw new SimulatedPaymentGateway.InvalidCallbackException("提供方不匹配");
            if (callback.type() == PaymentGateway.VerifiedCallback.CallbackType.PAYMENT) {
                payments.handleCallback(callback, rawBody);
            } else {
                refunds.handleCallback(callback, rawBody);
            }
            return json(200, "{\"status\":\"ok\"}");
        } catch (SimulatedPaymentGateway.InvalidCallbackException e) {
            return json(400, "{\"error\":\"回调验签失败\"}");
        } catch (IllegalArgumentException e) {
            return json(400, "{\"error\":\"回调格式无效\"}");
        }
    }

    @PostMapping(path = "/api/orders/{orderId}/payments", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> createPayment(@PathVariable java.util.UUID orderId,
                                                @RequestHeader("Idempotency-Key") String key) {
        try {
            PaymentService.PaymentResult result = payments.createPayment(orderId, key);
            return json(201, "{\"paymentId\":\"" + result.paymentId() + "\",\"providerReference\":\""
                + result.providerReference() + "\",\"status\":\"" + result.status() + "\"}");
        } catch (IllegalArgumentException e) { return json(400, "{\"error\":\"支付请求无效\"}"); }
        catch (IllegalStateException e) { return json(409, "{\"error\":\"订单不可支付\"}"); }
    }

    @GetMapping(path = "/api/payments/{paymentId}")
    public ResponseEntity<byte[]> queryPayment(@PathVariable java.util.UUID paymentId) {
        JdbcPaymentQuery result = query(paymentId);
        if (result == null) return json(404, "{\"error\":\"支付不存在\"}");
        return json(200, "{\"paymentId\":\"" + result.id() + "\",\"providerReference\":\""
            + result.reference() + "\",\"status\":\"" + result.status() + "\"}");
    }

    private JdbcPaymentQuery query(java.util.UUID id) {
        try {
            var row = payments.queryPayment(id);
            return row == null ? null : new JdbcPaymentQuery(row.paymentId(), row.providerReference(), row.status());
        } catch (RuntimeException e) { return null; }
    }
    private record JdbcPaymentQuery(java.util.UUID id, String reference, String status) {}

    private static ResponseEntity<byte[]> json(int status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.parseMediaType("application/json; charset=UTF-8"))
            .body(body.getBytes(StandardCharsets.UTF_8));
    }
}
