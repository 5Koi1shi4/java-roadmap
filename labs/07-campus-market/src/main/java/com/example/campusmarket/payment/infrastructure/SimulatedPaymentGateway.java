package com.example.campusmarket.payment.infrastructure;

import com.example.campusmarket.payment.application.PaymentGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 通过 HTTP 调用模拟提供方，保留可被 Toxiproxy 注入故障的真实网络边界。 */
@Component
@Profile({"local", "test"})
public class SimulatedPaymentGateway implements PaymentGateway {
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI baseUri;
    private final String provider;
    private final byte[] secret;
    private final ConcurrentHashMap<String, Long> usedNonces = new ConcurrentHashMap<>();

    @Autowired
    public SimulatedPaymentGateway(ObjectMapper mapper,
                                   @Value("${campus.market.payment.provider:simulated}") String provider,
                                   @Value("${campus.market.payment.provider-url:http://localhost:8080/simulated-provider}") String baseUrl,
                                   @Value("${campus.market.payment.signing-secret:local-only-payment-secret-change-me}") String secret) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), mapper, provider, baseUrl, secret);
    }

    public SimulatedPaymentGateway(HttpClient http, ObjectMapper mapper, String provider, String baseUrl, String secret) {
        this.http = http;
        this.mapper = mapper;
        this.provider = require(provider, "支付提供方");
        this.baseUri = URI.create(require(baseUrl, "模拟提供方地址").replaceAll("/$", "") + "/");
        this.secret = require(secret, "支付签名密钥").getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public PaymentCreated createPayment(CreatePaymentRequest request) {
        JsonNode body = post("/payments", request.idempotencyKey(), object("orderId", request.orderId().toString(), "amountFen", request.amount().fen(),
            "idempotencyKey", request.idempotencyKey()));
        return new PaymentCreated(text(body, "providerReference"), new PaymentStatus(text(body, "providerReference"),
            paymentStatus(text(body, "status")), number(body, "amountFen")));
    }

    @Override
    public PaymentStatus queryPayment(String providerReference) {
        JsonNode body = get("/payments/" + encode(providerReference));
        return new PaymentStatus(text(body, "providerReference"), paymentStatus(text(body, "status")), number(body, "amountFen"));
    }

    @Override
    public PaymentStatus queryPaymentByIdempotencyKey(String idempotencyKey) {
        JsonNode body = get("/payments/by-key/" + encode(idempotencyKey));
        return new PaymentStatus(text(body, "providerReference"), paymentStatus(text(body, "status")), number(body, "amountFen"));
    }

    @Override
    public RefundCreated requestRefund(CreateRefundRequest request) {
        JsonNode body = post("/refunds", request.idempotencyKey(), object("orderId", request.orderId().toString(), "paymentProviderReference",
            request.paymentProviderReference(), "amountFen", request.amount().fen(), "idempotencyKey", request.idempotencyKey()));
        return new RefundCreated(text(body, "providerReference"), RefundStatus.Status.valueOf(text(body, "status")), number(body, "amountFen"));
    }

    @Override
    public RefundStatus queryRefund(String providerReference) {
        JsonNode body = get("/refunds/" + encode(providerReference));
        return new RefundStatus(text(body, "providerReference"), RefundStatus.Status.valueOf(text(body, "status")), number(body, "amountFen"));
    }

    @Override
    public RefundStatus queryRefundByIdempotencyKey(String idempotencyKey) {
        JsonNode body = get("/refunds/by-key/" + encode(idempotencyKey));
        return new RefundStatus(text(body, "providerReference"), RefundStatus.Status.valueOf(text(body, "status")), number(body, "amountFen"));
    }

    @Override
    public VerifiedCallback verifyAndParse(byte[] rawBody, HttpHeaders headers) {
        if (rawBody == null || headers == null) throw new InvalidCallbackException("回调请求为空");
        String timestamp = first(headers, "X-Payment-Timestamp");
        String nonce = first(headers, "X-Payment-Nonce");
        String signature = first(headers, "X-Payment-Signature");
        if (timestamp == null || nonce == null || signature == null) throw new InvalidCallbackException("回调签名头缺失");
        long seconds;
        try { seconds = Long.parseLong(timestamp); } catch (NumberFormatException e) { throw new InvalidCallbackException("回调时间无效"); }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - seconds) > 300) throw new InvalidCallbackException("回调已过期");
        String expected = hmac(timestamp + "\n" + nonce + "\n" + new String(rawBody, StandardCharsets.UTF_8));
        if (!constantTimeSignatureEquals(signature, expected)) throw new InvalidCallbackException("回调签名无效");
        synchronized (usedNonces) {
            usedNonces.entrySet().removeIf(entry -> entry.getValue() <= now);
            String nonceKey = provider + ":" + nonce;
            if (!usedNonces.containsKey(nonceKey) && usedNonces.size() >= 10_000) {
                throw new InvalidCallbackException("回调 nonce 存储已满");
            }
            if (usedNonces.putIfAbsent(nonceKey, now + 300) != null) throw new InvalidCallbackException("回调 nonce 已重放");
        }
        try {
            JsonNode json = mapper.readTree(rawBody);
            java.util.Set<String> allowed = java.util.Set.of("providerEventId", "type", "providerReference", "amountFen", "status", "occurredAt", "orderId");
            java.util.Iterator<String> names = json.fieldNames();
            while (names.hasNext()) if (!allowed.contains(names.next())) throw new InvalidCallbackException("回调包含未知字段");
            requireFields(json, "providerEventId", "type", "providerReference", "amountFen", "status", "occurredAt");
            String type = text(json, "type");
            PaymentGateway.VerifiedCallback.CallbackType callbackType = PaymentGateway.VerifiedCallback.CallbackType.valueOf(type);
            String status = text(json, "status");
            if (!("SUCCEEDED".equals(status) || "FAILED".equals(status) || "PENDING".equals(status) || "UNKNOWN".equals(status))) throw new InvalidCallbackException("回调状态无效");
            if (number(json, "amountFen") <= 0 || text(json, "providerReference").isBlank()
                || text(json, "providerEventId").isBlank()) throw new InvalidCallbackException("回调字段边界无效");
            Instant occurred = json.get("occurredAt").isNumber() ? Instant.ofEpochSecond(json.get("occurredAt").longValue()) : Instant.parse(text(json, "occurredAt"));
            java.util.UUID orderId = json.hasNonNull("orderId") ? java.util.UUID.fromString(text(json, "orderId")) : null;
            return new VerifiedCallback(provider, text(json, "providerEventId"), callbackType,
                text(json, "providerReference"), number(json, "amountFen"), status, occurred, nonce, orderId);
        } catch (InvalidCallbackException e) { throw e; }
        catch (Exception e) { throw new InvalidCallbackException("回调 JSON 无效"); }
    }

    private JsonNode post(String path, String idempotencyKey, ObjectNodeBuilder fields) {
        try {
            byte[] data = mapper.writeValueAsBytes(fields.values);
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(endpoint(path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json; charset=UTF-8")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(data)).build(), HttpResponse.BodyHandlers.ofByteArray());
            return decode(response);
        } catch (Exception e) { throw new PaymentGatewayUnavailableException(e); }
    }
    private JsonNode get(String path) {
        try {
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(endpoint(path)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            return decode(response);
        } catch (Exception e) { throw new PaymentGatewayUnavailableException(e); }
    }
    private JsonNode decode(HttpResponse<byte[]> response) throws Exception {
        if (response.statusCode() / 100 != 2) throw new PaymentGatewayUnavailableException("提供方返回 " + response.statusCode());
        return mapper.readTree(response.body());
    }
    private String hmac(String value) {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) { throw new IllegalStateException("支付签名不可用", e); }
    }
    private static boolean constantTimeSignatureEquals(String actual, String expected) {
        try {
            byte[] a = decodeSignature(actual); byte[] b = decodeSignature(expected);
            return MessageDigest.isEqual(a, b);
        } catch (IllegalArgumentException e) { return false; }
    }
    private static byte[] decodeSignature(String value) {
        try { return HexFormat.of().parseHex(value); } catch (Exception ignored) { return Base64.getDecoder().decode(value); }
    }
    private static String first(HttpHeaders headers, String name) { return headers.getFirst(name); }
    private static String text(JsonNode node, String name) { if (!node.hasNonNull(name)) throw new InvalidCallbackException("字段缺失: " + name); return node.get(name).asText(); }
    private static long number(JsonNode node, String name) { if (!node.has(name) || !node.get(name).canConvertToLong()) throw new InvalidCallbackException("金额字段无效"); return node.get(name).longValue(); }
    private static void requireFields(JsonNode node, String... fields) { for (String field : fields) text(node, field); }
    private static PaymentStatus.Status paymentStatus(String status) {
        try { return PaymentStatus.Status.valueOf(status); }
        catch (Exception e) { throw new PaymentGatewayUnavailableException("提供方状态无效"); }
    }
    private static String require(String value, String name) { if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空"); return value; }
    private static String encode(String value) { return value.replace("/", "%2F"); }
    private URI endpoint(String path) { return baseUri.resolve(path.startsWith("/") ? path.substring(1) : path); }
    private static ObjectNodeBuilder object(Object... fields) { return new ObjectNodeBuilder(fields); }
    private static final class ObjectNodeBuilder { final java.util.Map<String,Object> values = new java.util.LinkedHashMap<>(); ObjectNodeBuilder(Object... f) { for (int i=0;i<f.length;i+=2) values.put((String)f[i], f[i+1]); } }
    public static class InvalidCallbackException extends RuntimeException { public InvalidCallbackException(String m) { super(m); } }
    public static class PaymentGatewayUnavailableException extends RuntimeException { public PaymentGatewayUnavailableException(Object m) { super(m instanceof Throwable ? (Throwable)m : new IllegalStateException(String.valueOf(m))); } }
}
