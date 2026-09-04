package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.payment.application.PaymentGateway;
import com.example.campusmarket.payment.infrastructure.SimulatedPaymentGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.HttpHeaders;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 模拟适配器契约：真实 HTTP provider、金额分、查询和回调验签。 */
@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestPropertySource(properties = "server.address=0.0.0.0")
class PaymentGatewayContractIT extends SharedContainers {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void createsAndQueriesPaymentOverRealHttp() {
        PaymentGateway gateway = gateway();
        String key = UUID.randomUUID().toString();
        UUID order = UUID.randomUUID();
        PaymentGateway.PaymentCreated created = gateway.createPayment(new PaymentGateway.CreatePaymentRequest(
            order, com.example.campusmarket.shared.Money.ofFen(1234), key));
        assertThat(created.status().status()).isEqualTo(PaymentGateway.PaymentStatus.Status.PENDING);
        assertThat(gateway.createPayment(new PaymentGateway.CreatePaymentRequest(
            order, com.example.campusmarket.shared.Money.ofFen(1234), key)).providerReference())
            .isEqualTo(created.providerReference());
        assertThat(gateway.queryPayment(created.providerReference()).status())
            .isEqualTo(PaymentGateway.PaymentStatus.Status.PENDING);
    }

    @Test
    void rejectsExpiredAndReplayedSignedCallback() throws Exception {
        PaymentGateway gateway = gateway();
        String nonce = UUID.randomUUID().toString();
        String body = objectMapper.writeValueAsString(java.util.Map.of("providerEventId", UUID.randomUUID().toString(),
            "type", "PAYMENT", "providerReference", "sim-pay-known", "amountFen", 100,
            "status", "SUCCEEDED", "occurredAt", Instant.now().toString()));
        HttpHeaders headers = signed(body, nonce, Instant.now().getEpochSecond());
        assertThat(gateway.verifyAndParse(body.getBytes(StandardCharsets.UTF_8), headers).provider()).isEqualTo("simulated");
        assertThatThrownBy(() -> gateway.verifyAndParse(body.getBytes(StandardCharsets.UTF_8), headers))
            .isInstanceOf(SimulatedPaymentGateway.InvalidCallbackException.class);
        HttpHeaders expired = signed(body, UUID.randomUUID().toString(), Instant.now().minusSeconds(301).getEpochSecond());
        assertThatThrownBy(() -> gateway.verifyAndParse(body.getBytes(StandardCharsets.UTF_8), expired))
            .isInstanceOf(SimulatedPaymentGateway.InvalidCallbackException.class);
    }

    @Test
    void requestsAndQueriesRefundOverRealHttpAndRejectsDifferentIdempotencyRequest() {
        PaymentGateway gateway = gateway();
        String key = UUID.randomUUID().toString();
        UUID order = UUID.randomUUID();
        PaymentGateway.CreateRefundRequest request = new PaymentGateway.CreateRefundRequest(
            order, "sim-pay-reference", com.example.campusmarket.shared.Money.ofFen(321), key);
        PaymentGateway.RefundCreated created = gateway.requestRefund(request);
        assertThat(created.status()).isEqualTo(PaymentGateway.RefundStatus.Status.PENDING);
        assertThat(gateway.queryRefund(created.providerReference()).status()).isEqualTo(PaymentGateway.RefundStatus.Status.PENDING);
        assertThat(gateway.requestRefund(request).providerReference()).isEqualTo(created.providerReference());
        assertThatThrownBy(() -> gateway.requestRefund(new PaymentGateway.CreateRefundRequest(
            order, "different-reference", com.example.campusmarket.shared.Money.ofFen(321), key)))
            .isInstanceOf(SimulatedPaymentGateway.PaymentGatewayUnavailableException.class);
    }

    @Test
    void unknownReferencesRemainExplicitUnknownWithoutCreatingARequest() {
        PaymentGateway gateway = gateway();
        assertThat(gateway.queryPayment("not-created-" + UUID.randomUUID()).status())
            .isEqualTo(PaymentGateway.PaymentStatus.Status.UNKNOWN);
        assertThat(gateway.queryRefund("not-created-" + UUID.randomUUID()).status())
            .isEqualTo(PaymentGateway.RefundStatus.Status.UNKNOWN);
    }

    @Test
    void rejectsTamperedSignatureAndUnknownCallbackField() throws Exception {
        PaymentGateway gateway = gateway();
        String nonce = UUID.randomUUID().toString();
        String body = "{\"providerEventId\":\"evt-" + UUID.randomUUID() + "\",\"type\":\"PAYMENT\",\"providerReference\":\"p\",\"amountFen\":1,\"status\":\"SUCCEEDED\",\"occurredAt\":\"" + Instant.now() + "\",\"secretCredential\":\"must-not-persist\"}";
        HttpHeaders headers = signed(body, nonce, Instant.now().getEpochSecond());
        headers.set("X-Payment-Signature", "00");
        assertThatThrownBy(() -> gateway.verifyAndParse(body.getBytes(StandardCharsets.UTF_8), headers))
            .isInstanceOf(SimulatedPaymentGateway.InvalidCallbackException.class);
        HttpHeaders unknownField = signed(body, UUID.randomUUID().toString(), Instant.now().getEpochSecond());
        assertThatThrownBy(() -> gateway.verifyAndParse(body.getBytes(StandardCharsets.UTF_8), unknownField))
            .isInstanceOf(SimulatedPaymentGateway.InvalidCallbackException.class);
        String invalidStatusBody = body.replace(",\"secretCredential\":\"must-not-persist\"", "")
            .replace("\"SUCCEEDED\"", "\"NOT_A_STATUS\"");
        HttpHeaders invalidStatus = signed(invalidStatusBody, UUID.randomUUID().toString(), Instant.now().getEpochSecond());
        assertThatThrownBy(() -> gateway.verifyAndParse(invalidStatusBody.getBytes(StandardCharsets.UTF_8), invalidStatus))
            .isInstanceOf(SimulatedPaymentGateway.InvalidCallbackException.class);
    }

    @Test
    void providerRejectsUnknownFieldsAndInvalidStatusWithUtf8Error() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String unknown = "{\"orderId\":\"" + UUID.randomUUID() + "\",\"amountFen\":1,\"idempotencyKey\":\"utf8-" + UUID.randomUUID() + "\",\"unexpected\":true}";
        HttpResponse<byte[]> unknownResponse = client.send(HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/simulated-provider/payments"))
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(unknown, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(unknownResponse.statusCode()).isEqualTo(400);
        assertThat(new String(unknownResponse.body(), StandardCharsets.UTF_8)).contains("请求参数无效");
        String invalid = "{\"orderId\":\"" + UUID.randomUUID() + "\",\"amountFen\":1,\"idempotencyKey\":\"bad-status-" + UUID.randomUUID() + "\"}";
        HttpResponse<byte[]> invalidResponse = client.send(HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/simulated-provider/payments/no-such/NOT_A_STATUS"))
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(invalid, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(invalidResponse.statusCode()).isEqualTo(400);
        assertThat(new String(invalidResponse.body(), StandardCharsets.UTF_8)).contains("请求参数无效");
    }

    @Test
    void toxiproxyDisconnectTimesOutAndRecoversAgainstRealHttpProviderBoundary() {
        org.testcontainers.containers.ToxiproxyContainer.ContainerProxy proxy =
            TOXIPROXY.getProxy(PAYMENT_PROVIDER_HTTP, 8080);
        PaymentGateway throughProxy = new SimulatedPaymentGateway(HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(1)).build(), objectMapper,
            "simulated", "http://" + TOXIPROXY.getHost() + ":" + proxy.getProxyPort(),
            "local-only-payment-secret-change-me");
        proxy.setConnectionCut(true);
        assertThatThrownBy(() -> throughProxy.queryPayment("probe"))
            .isInstanceOf(SimulatedPaymentGateway.PaymentGatewayUnavailableException.class);
        proxy.setConnectionCut(false);
        PaymentGateway.PaymentStatus recovered = null;
        for (int attempt = 0; attempt < 10 && recovered == null; attempt++) {
            try {
                recovered = throughProxy.queryPayment("probe");
            } catch (SimulatedPaymentGateway.PaymentGatewayUnavailableException retryable) {
                try { Thread.sleep(250); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
        }
        assertThat(recovered).isNotNull();
        assertThat(recovered.status()).isEqualTo(PaymentGateway.PaymentStatus.Status.UNKNOWN);
    }

    private PaymentGateway gateway() {
        return new SimulatedPaymentGateway(HttpClient.newHttpClient(), objectMapper, "simulated",
            "http://localhost:" + port + "/simulated-provider", "local-only-payment-secret-change-me");
    }
    private static HttpHeaders signed(String body, String nonce, long timestamp) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("local-only-payment-secret-change-me".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String input = timestamp + "\n" + nonce + "\n" + body;
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Payment-Timestamp", Long.toString(timestamp));
        headers.set("X-Payment-Nonce", nonce);
        headers.set("X-Payment-Signature", java.util.HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8))));
        return headers;
    }
}
