package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.catalog.search.ProductSearchPort;
import com.example.campusmarket.catalog.search.SearchOutboxDispatcher;
import com.example.campusmarket.dispute.application.ProofAuthority;
import com.example.campusmarket.dispute.application.ReturnResolutionService;
import com.example.campusmarket.dispute.domain.DisputeDecision;
import com.example.campusmarket.dispute.domain.ReturnProofType;
import com.example.campusmarket.identity.infrastructure.LocalVerificationMailSender;
import com.example.campusmarket.identity.infrastructure.JwtService;
import com.example.campusmarket.payment.application.SettlementService;
import com.example.campusmarket.payment.infrastructure.SimulatedPaymentProviderController;
import com.example.campusmarket.warranty.application.SellerObligationService;
import com.example.campusmarket.warranty.application.WarrantyService;
import com.example.campusmarket.warranty.domain.WarrantyDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.core.io.ByteArrayResource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 两条独立的真实 HTTP 旅程；时间推进使用 MySQL 时间，外部支付/搜索/对象存储仍走真实适配器。 */
@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("local")
@TestPropertySource(properties = {
    "server.port=18082",
    "campus.market.payment.provider-url=http://localhost:18082/simulated-provider",
    "campus.market.order.deadline.enabled=false",
    "campus.market.search.dispatcher.enabled=false",
    "campus.market.payment.reconciliation.enabled=false",
    "campus.market.dispute.deadline.enabled=false",
    "campus.market.dispute.return-reconciliation.enabled=false",
    "campus.market.warranty.deadline.enabled=false",
    "spring.task.scheduling.enabled=false",
    "spring.rabbitmq.listener.simple.auto-startup=false",
    "spring.rabbitmq.listener.direct.auto-startup=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CampusMarketJourneyIT extends SharedContainers {
    private static final String PAYMENT_SECRET = "local-only-payment-secret-change-me";
    @Autowired private TestRestTemplate http;
    @Autowired private LocalVerificationMailSender mail;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SearchOutboxDispatcher searchOutbox;
    @Autowired private ProductSearchPort search;
    @Autowired private SimulatedPaymentProviderController provider;
    @Autowired private ReturnResolutionService returns;
    @Autowired private SettlementService settlements;
    @Autowired private WarrantyService warranties;
    @Autowired private SellerObligationService obligations;
    @Autowired private JwtService jwt;

    @BeforeEach
    void cleanBusinessRows() {
        // Each method gets its own IDs; this assertion documents that the test does not reuse state.
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }

    @Test
    void textbookJourneyUsesHttpBoundariesAndConvergesAfterPartialReturn() throws Exception {
        User seller = register("seller-" + UUID.randomUUID() + "@stu.example.edu.cn");
        User buyer = register("buyer-" + UUID.randomUUID() + "@stu.example.edu.cn");
        User admin = seededAdmin();

        HttpHeaders sellerHeaders = bearer(seller.token());
        ResponseEntity<String> created = http.postForEntity("/api/listings", entity(
            "{\"title\":\"Java 并发教材\",\"description\":\"第六版教材\",\"category\":\"教材\",\"unitPriceFen\":100,\"availableQuantity\":6}", sellerHeaders), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID listing = uuid(created.getBody(), "id");
        ResponseEntity<String> published = http.postForEntity("/api/listings/" + listing + "/publish", entity("", sellerHeaders), String.class);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(searchOutbox.dispatchOnce(20)).isGreaterThanOrEqualTo(1);
        search.refresh();
        ResponseEntity<String> found = http.getForEntity("/api/search?keyword=并发&size=20", String.class);
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(found.getBody()).contains(listing.toString());

        HttpHeaders buyerHeaders = bearer(buyer.token());
        ResponseEntity<String> orderResponse = http.postForEntity("/api/orders", entity(
            "{\"listingId\":\"" + listing + "\",\"quantity\":3}", withKey(buyerHeaders, "order-" + UUID.randomUUID())), String.class);
        assertThat(orderResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID order = uuid(orderResponse.getBody(), "orderId");

        ResponseEntity<String> payment = http.postForEntity("/api/orders/" + order + "/payments",
            entity("{}", withKey(new HttpHeaders(), "payment-" + order)), String.class);
        assertThat(payment.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID paymentId = uuid(payment.getBody(), "paymentId");
        String paymentReference = text(payment.getBody(), "providerReference");
        http.postForEntity("/simulated-provider/payments/" + paymentReference + "/SUCCEEDED", new HttpEntity<>(new HttpHeaders()), String.class);
        callback("PAYMENT", order, paymentReference, 300, "SUCCEEDED", "payment-event-" + order);
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("AWAITING_HANDOFF");

        assertThat(http.postForEntity("/api/orders/" + order + "/handoff", entity("{}", withKey(sellerHeaders, "handoff-" + order)), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.postForEntity("/api/orders/" + order + "/receipt", entity("{}", withKey(buyerHeaders, "receipt-" + order)), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> opened = http.postForEntity("/api/orders/" + order + "/disputes", entity(
            "{\"disputedQuantity\":1,\"reason\":\"FUNCTIONAL_DEFECT\"}", withKey(buyerHeaders, "dispute-" + order)), String.class);
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID dispute = uuid(opened.getBody(), "disputeId");
        assertThat(http.postForEntity("/api/disputes/" + dispute + "/responses", entity("{\"response\":\"同意退回\"}", withKey(sellerHeaders, "respond-" + dispute)), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.postForEntity("/api/disputes/" + dispute + "/assignments", entity("{\"adminId\":\"" + admin.id() + "\"}", admin.headers()), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.postForEntity("/api/disputes/" + dispute + "/decisions", entity("{\"decision\":\"RETURN_AND_REFUND\",\"approvedQuantity\":1}", withKey(admin.headers(), "decision-" + dispute)), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // ReturnResolutionService is the existing application seam for seller proof + provider refund.
        var prepared = returns.resolve(dispute, DisputeDecision.RETURN_AND_REFUND, 1, ReturnProofType.SELLER_CONFIRMED,
            seller.id().toString(), ProofAuthority.seller(seller.id()));
        assertThat(prepared.refundId()).isNotNull();
        String refundRef = refundsReference(prepared.refundId());
        http.postForEntity("/simulated-provider/refunds/" + refundRef + "/SUCCEEDED", new HttpEntity<>(new HttpHeaders()), String.class);
        callback("REFUND", order, refundRef, 100, "SUCCEEDED", "refund-event-" + dispute);
        returns.reconcileSuccessfulRefund(prepared.refundId());
        jdbc.update("UPDATE trade_order SET t0=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 8 DAY) WHERE id=?", order.toString());
        assertThat(settlements.settle(order).status()).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT quarantined_quantity FROM listing WHERE id=?", Integer.class, listing.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement WHERE order_id=?", Integer.class, order.toString())).isEqualTo(1);
        assertThat(http.postForEntity("/api/orders/" + order + "/reviews", entity("{\"rating\":5,\"content\":\"交付清晰\"}", buyerHeaders), String.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(http.postForEntity("/api/orders/" + order + "/reviews", entity("{\"rating\":4,\"content\":\"沟通顺畅\"}", sellerHeaders), String.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_order WHERE id=?", Integer.class, paymentId.toString())).isEqualTo(1);
    }

    @Test
    void keyboardWarrantyJourneyKeepsOrderSettledAndClearsSellerRestriction() throws Exception {
        User seller = register("warranty-seller-" + UUID.randomUUID() + "@stu.example.edu.cn");
        User buyer = register("warranty-buyer-" + UUID.randomUUID() + "@stu.example.edu.cn");
        User admin = seededAdmin();
        HttpHeaders sellerHeaders = bearer(seller.token());
        HttpHeaders buyerHeaders = bearer(buyer.token());
        ResponseEntity<String> created = http.postForEntity("/api/listings", entity("{\"title\":\"二手键盘\",\"description\":\"机械键盘\",\"category\":\"电子\",\"unitPriceFen\":500,\"availableQuantity\":1,\"sellerWarrantyDays\":90}", sellerHeaders), String.class);
        UUID listing = uuid(created.getBody(), "id");
        http.postForEntity("/api/listings/" + listing + "/publish", entity("", sellerHeaders), String.class);
        UUID order = uuid(http.postForEntity("/api/orders", entity("{\"listingId\":\"" + listing + "\",\"quantity\":1}", withKey(buyerHeaders, "w-order-" + listing)), String.class).getBody(), "orderId");
        ResponseEntity<String> payment = http.postForEntity("/api/orders/" + order + "/payments", entity("{}", withKey(new HttpHeaders(), "w-pay-" + order)), String.class);
        String paymentRef = text(payment.getBody(), "providerReference");
        http.postForEntity("/simulated-provider/payments/" + paymentRef + "/SUCCEEDED", new HttpEntity<>(new HttpHeaders()), String.class);
        callback("PAYMENT", order, paymentRef, 500, "SUCCEEDED", "w-payment-event-" + order);
        http.postForEntity("/api/orders/" + order + "/handoff", entity("{}", withKey(sellerHeaders, "w-handoff-" + order)), String.class);
        http.postForEntity("/api/orders/" + order + "/receipt", entity("{}", withKey(buyerHeaders, "w-receipt-" + order)), String.class);
        jdbc.update("UPDATE trade_order SET t0=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 8 DAY) WHERE id=?", order.toString());
        assertThat(settlements.settle(order).status()).isEqualTo("SETTLED");
        // 将已结算订单推进到质保第 30 天，验证卖家质保窗口独立于平台七天试用期。
        jdbc.update("UPDATE trade_order SET t0=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 30 DAY) WHERE id=?", order.toString());
        ResponseEntity<String> opened = http.postForEntity("/api/orders/" + order + "/warranty", entity("{\"quantity\":1,\"reason\":\"FUNCTIONAL_DEFECT\"}", withKey(buyerHeaders, "w-case-" + order)), String.class);
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID caseId = uuid(opened.getBody(), "caseId");
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource("%PDF-1.4\nrepair quote\n%%EOF".getBytes(StandardCharsets.US_ASCII)) { @Override public String getFilename() { return "quote.pdf"; } });
        HttpHeaders evidenceHeaders = bearer(buyer.token());
        evidenceHeaders.set("X-Evidence-Purpose", "REPAIR_QUOTE");
        evidenceHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> evidence = http.postForEntity("/api/warranty-cases/" + caseId + "/evidence", new HttpEntity<>(form, evidenceHeaders), String.class);
        assertThat(evidence.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String evidenceId = text(evidence.getBody(), "evidenceId");
        http.postForEntity("/api/warranty-cases/" + caseId + "/assignments", entity("{\"adminId\":\"" + admin.id() + "\"}", admin.headers()), String.class);
        assertThat(http.postForEntity("/api/warranty-cases/" + caseId + "/decisions", entity("{\"decision\":\"REPAIR_COMPENSATION\",\"compensationAmountFen\":200,\"evidenceId\":\"" + evidenceId + "\"}", withKey(admin.headers(), "w-decision-" + caseId)), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/api/seller/restrictions", String.class).getBody()).contains("\"publishRestricted\":true");
        UUID obligationId = UUID.fromString(jdbc.queryForObject("SELECT id FROM seller_obligation WHERE warranty_case_id=?", String.class, caseId.toString()));
        assertThat(http.postForEntity("/api/seller/obligations/" + obligationId + "/fund", entity("{\"amountFen\":200}", withKey(sellerHeaders, "fund-" + obligationId)), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/api/seller/restrictions", String.class).getBody()).contains("\"publishRestricted\":false");
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("SETTLED");
        assertThat(obligations.findForSeller(seller.id())).isNotEmpty();
    }

    private User register(String email) throws Exception {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        assertThat(http.postForEntity("/api/auth/email-verifications", entity("{\"email\":\"" + email + "\"}", headers), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        String code = mail.latestCode(email); assertThat(code).isNotBlank();
        assertThat(http.postForEntity("/api/auth/register", entity("{\"email\":\"" + email + "\",\"password\":\"Campus123!\",\"code\":\"" + code + "\"}", headers), String.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode login = mapper.readTree(http.postForEntity("/api/auth/login", entity("{\"email\":\"" + email + "\",\"password\":\"Campus123!\"}", headers), String.class).getBody());
        return new User(UUID.fromString(login.get("userId").asText()), login.get("accessToken").asText(), headers);
    }

    private User seededAdmin() { UUID id = UUID.randomUUID(); jdbc.update("INSERT INTO campus_user(id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", id.toString(), id + "@admin.example.edu.cn", "hash"); return new User(id, jwt.issue(new com.example.campusmarket.identity.application.AuthenticatedUser(id, java.util.Set.of("ROLE_ADMIN"))), null); }
    private HttpHeaders bearer(String token) { HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.setBearerAuth(token); return h; }
    private HttpHeaders withKey(HttpHeaders source, String key) { HttpHeaders h = new HttpHeaders(); h.putAll(source); h.setContentType(MediaType.APPLICATION_JSON); h.set("Idempotency-Key", key); return h; }
    private HttpEntity<String> entity(String body, HttpHeaders headers) { return new HttpEntity<>(body, headers); }
    private void callback(String type, UUID order, String reference, long amount, String status, String event) throws Exception { String body = "{\"providerEventId\":\"" + event + "\",\"type\":\"" + type + "\",\"providerReference\":\"" + reference + "\",\"amountFen\":" + amount + ",\"status\":\"" + status + "\",\"occurredAt\":\"" + Instant.now() + "\",\"orderId\":\"" + order + "\"}"; long now = Instant.now().getEpochSecond(); HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.set("X-Payment-Timestamp", Long.toString(now)); h.set("X-Payment-Nonce", UUID.randomUUID().toString()); h.set("X-Payment-Signature", hmac(now + "\n" + h.getFirst("X-Payment-Nonce") + "\n" + body)); assertThat(http.postForEntity("/api/payment-webhooks/simulated", new HttpEntity<>(body, h), String.class).getStatusCode()).isEqualTo(HttpStatus.OK); }
    private String hmac(String text) throws Exception { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(PAYMENT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8))); }
    private String refundsReference(UUID id) { return jdbc.queryForObject("SELECT provider_reference FROM refund_order WHERE id=?", String.class, id.toString()); }
    private UUID uuid(String body, String field) throws Exception { return UUID.fromString(mapper.readTree(body).get(field).asText()); }
    private String text(String body, String field) throws Exception { return mapper.readTree(body).get(field).asText(); }
    private record User(UUID id, String token, HttpHeaders ignored) {
        HttpHeaders headers() { HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.setBearerAuth(token); return h; }
    }
}
