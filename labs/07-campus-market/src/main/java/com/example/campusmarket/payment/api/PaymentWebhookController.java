package com.example.campusmarket.payment.api;

import com.example.campusmarket.payment.application.PaymentGateway;
import com.example.campusmarket.api.ApiErrors;
import com.example.campusmarket.payment.application.PaymentService;
import com.example.campusmarket.payment.application.RefundService;
import com.example.campusmarket.payment.infrastructure.SimulatedPaymentGateway;
import com.example.campusmarket.observability.CampusMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import com.example.campusmarket.shared.Money;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestController
@Profile("!test")
@RequestMapping(produces = "application/json; charset=UTF-8")
public class PaymentWebhookController {
    private final PaymentGateway gateway;
    private final PaymentService payments;
    private final RefundService refunds;
    private final ObjectMapper mapper;
    private final CampusMetrics metrics;

    public PaymentWebhookController(PaymentGateway gateway, PaymentService payments, RefundService refunds, ObjectMapper mapper,
                                    CampusMetrics metrics) {
        this.gateway = Objects.requireNonNull(gateway, "支付网关不能为空");
        this.payments = Objects.requireNonNull(payments, "支付服务不能为空");
        this.refunds = Objects.requireNonNull(refunds, "退款服务不能为空");
        this.mapper = Objects.requireNonNull(mapper, "JSON序列化器不能为空");
        this.metrics = Objects.requireNonNull(metrics, "指标门面不能为空");
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
            metrics.recordPaymentCallback("FAILURE");
            return ApiErrors.bytes(org.springframework.http.HttpStatus.BAD_REQUEST, "回调验签失败");
        } catch (IllegalArgumentException e) {
            metrics.recordPaymentCallback("FAILURE");
            return ApiErrors.bytes(org.springframework.http.HttpStatus.BAD_REQUEST, "回调格式无效");
        }
    }

    @PostMapping(path = "/api/orders/{orderId}/payments", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> createPayment(@PathVariable java.util.UUID orderId,
                                                @RequestBody(required = false) byte[] rawRequest,
                                                @RequestHeader("Idempotency-Key") String key) {
        try {
            if (rawRequest == null || rawRequest.length == 0 || key == null || key.isBlank()) return ApiErrors.bytes(org.springframework.http.HttpStatus.BAD_REQUEST, "请求参数无效");
            PaymentService.PaymentResult result = payments.createPayment(orderId, key, rawRequest);
            int status = "UNKNOWN".equals(result.status()) ? 503 : 201;
            return result.responseUtf8() == null ? json(status, "{\"paymentId\":\"" + result.paymentId() + "\",\"providerReference\":\""
                + result.providerReference() + "\",\"status\":\"" + result.status() + "\"}") : bytes(status, result.responseUtf8());
        } catch (IllegalArgumentException e) { return ApiErrors.bytes(org.springframework.http.HttpStatus.BAD_REQUEST, "支付请求无效"); }
        catch (PaymentService.IdempotencyConflictException e) { return ApiErrors.bytes(org.springframework.http.HttpStatus.CONFLICT, "幂等冲突"); }
        catch (IllegalStateException e) { return ApiErrors.bytes(org.springframework.http.HttpStatus.CONFLICT, "订单不可支付"); }
    }

    @GetMapping(path = "/api/payments/{paymentId}")
    public ResponseEntity<byte[]> queryPayment(@PathVariable java.util.UUID paymentId) {
        JdbcPaymentQuery result = query(paymentId);
        if (result == null) return ApiErrors.bytes(org.springframework.http.HttpStatus.NOT_FOUND, "支付不存在");
        return result.raw() == null ? json(200, "{\"paymentId\":\"" + result.id() + "\",\"providerReference\":\""
            + result.reference() + "\",\"status\":\"" + result.status() + "\"}") : bytes(200, result.raw());
    }

    private JdbcPaymentQuery query(java.util.UUID id) {
        var row = payments.queryPayment(id);
        return row == null ? null : new JdbcPaymentQuery(row.paymentId(), row.providerReference(), row.status(), row.responseUtf8());
    }
    private record JdbcPaymentQuery(java.util.UUID id, String reference, String status, byte[] raw) {}

    @PostMapping(path = {"/api/orders/{orderId}/refunds", "/api/refunds"}, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> requestRefund(@PathVariable(required = false) java.util.UUID orderId,
        @RequestBody(required = false) byte[] rawBody,
                                               @RequestHeader("Idempotency-Key") String key) {
        if (rawBody == null || rawBody.length == 0 || key == null || key.isBlank()) {
            return ApiErrors.bytes(org.springframework.http.HttpStatus.BAD_REQUEST, "退款请求无效");
        }
        RefundRequest request;
        try { request = mapper.readValue(rawBody, RefundRequest.class); }
        catch (Exception invalidJson) { return ApiErrors.bytes(org.springframework.http.HttpStatus.BAD_REQUEST, "退款请求无效"); }
        if (request == null || request.amountFen() <= 0 || (orderId == null && request.orderId() == null))
            return ApiErrors.bytes(org.springframework.http.HttpStatus.BAD_REQUEST, "退款请求无效");
        java.util.UUID actualOrder = orderId == null ? request.orderId() : orderId;
        try {
            var result = refunds.requestRefund(actualOrder, key, Money.ofFen(request.amountFen()), rawBody);
            int status = "UNKNOWN".equals(result.status()) ? 503 : 201;
            return result.responseUtf8() == null ? json(status, "{\"refundId\":\"" + result.refundId() + "\",\"providerReference\":\""
                + result.providerReference() + "\",\"status\":\"" + result.status() + "\"}") : bytes(status, result.responseUtf8());
        } catch (RefundService.RefundLimitExceededException | RefundService.IdempotencyConflictException e) {
            return ApiErrors.bytes(org.springframework.http.HttpStatus.CONFLICT, "退款额度或幂等冲突");
        } catch (IllegalArgumentException e) { return ApiErrors.bytes(org.springframework.http.HttpStatus.BAD_REQUEST, "退款请求无效"); }
        catch (IllegalStateException e) { return ApiErrors.bytes(org.springframework.http.HttpStatus.CONFLICT, "订单不可退款"); }
    }

    @GetMapping(path = "/api/refunds/{refundId}")
    public ResponseEntity<byte[]> queryRefund(@PathVariable java.util.UUID refundId) {
        var result = refunds.queryRefund(refundId);
        if (result == null) return ApiErrors.bytes(org.springframework.http.HttpStatus.NOT_FOUND, "退款不存在");
        return result.responseUtf8() == null ? json(200, "{\"refundId\":\"" + result.refundId() + "\",\"providerReference\":\""
            + result.providerReference() + "\",\"status\":\"" + result.status() + "\"}") : bytes(200, result.responseUtf8());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<byte[]> databaseFailure(DataAccessException ignored) {
        return ApiErrors.bytes(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "支付依赖暂不可用");
    }
    @ExceptionHandler({MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
    ResponseEntity<byte[]> protocolFailure(Exception ignored) {
        return ApiErrors.bytes(org.springframework.http.HttpStatus.BAD_REQUEST, "请求参数无效");
    }
    public record RefundRequest(java.util.UUID orderId, long amountFen) {}

    private static ResponseEntity<byte[]> json(int status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.parseMediaType("application/json; charset=UTF-8"))
            .body(body.getBytes(StandardCharsets.UTF_8));
    }
    private static ResponseEntity<byte[]> bytes(int status, byte[] body) {
        return ResponseEntity.status(status).contentType(MediaType.parseMediaType("application/json; charset=UTF-8")).body(body);
    }
}
