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
import org.springframework.http.HttpHeaders;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 模拟适配器契约：真实 HTTP provider、金额分、查询和回调验签。 */
@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class PaymentGatewayContractIT extends SharedContainers {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void createsAndQueriesPaymentOverRealHttp() {
        PaymentGateway gateway = gateway();
        String key = UUID.randomUUID().toString();
        PaymentGateway.PaymentCreated created = gateway.createPayment(new PaymentGateway.CreatePaymentRequest(
            UUID.randomUUID(), com.example.campusmarket.shared.Money.ofFen(1234), key));
        assertThat(created.status().status()).isEqualTo(PaymentGateway.PaymentStatus.Status.PENDING);
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
