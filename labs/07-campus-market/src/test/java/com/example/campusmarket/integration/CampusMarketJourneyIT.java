package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.ClassOrderer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 两条独立的真实 HTTP 旅程；每个阶段使用自己的最小容器集合并在阶段结束后回收。 */
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class CampusMarketJourneyIT {
    @Nested
    @Order(1)
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
        "spring.task.scheduling.enabled=false"
    })
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class TextbookJourney extends TextbookContainers {
        @AfterAll
        static void stopContainers() {
            stopTextbookContainers();
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
            assertThat(uploadListingMedia(listing, seller).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            // 释放 MinIO 后再启动 ES，保持同一 HTTP/数据库旅程且避免低内存主机重叠重型容器。
            stopTextbookMediaContainer();
            startTextbookSearchContainer();
            ResponseEntity<String> published = http.postForEntity("/api/listings/" + listing + "/publish", entity("", sellerHeaders), String.class);
            assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(searchOutbox.dispatchOnce(20)).isGreaterThanOrEqualTo(1);
            search.refresh();
            ResponseEntity<String> found = http.exchange("/api/search?keyword=并发&size=20", HttpMethod.GET, entity("", sellerHeaders), String.class);
            assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(found.getBody()).contains(listing.toString());

            HttpHeaders buyerHeaders = bearer(buyer.token());
            ResponseEntity<String> orderResponse = http.postForEntity("/api/orders", entity(
                "{\"listingId\":\"" + listing + "\",\"quantity\":3}", withKey(buyerHeaders, "order-" + UUID.randomUUID())), String.class);
            assertThat(orderResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            UUID order = uuid(orderResponse.getBody(), "orderId");

            ResponseEntity<String> payment = http.postForEntity("/api/orders/" + order + "/payments",
                entity("{}", withKey(buyerHeaders, "payment-" + order)), String.class);
            assertThat(payment.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            UUID paymentId = uuid(payment.getBody(), "paymentId");
            String paymentReference = text(payment.getBody(), "providerReference");
            ResponseEntity<String> providerPayment = http.postForEntity("/simulated-provider/payments/" + paymentReference + "/SUCCEEDED", new HttpEntity<>(new HttpHeaders()), String.class);
            assertThat(providerPayment.getStatusCode()).isEqualTo(HttpStatus.OK);
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

            ResponseEntity<String> confirmed = http.postForEntity("/api/disputes/" + dispute + "/return-confirmations", entity(
                "{\"proofReference\":\"seller-confirmed-" + dispute + "\"}", withKey(sellerHeaders, "return-confirm-" + dispute)), String.class);
            assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
            UUID confirmedRefund = uuid(confirmed.getBody(), "refundId");
            assertThat(confirmedRefund).isNotNull();
            String refundRef = refundsReference(confirmedRefund);
            ResponseEntity<String> providerRefund = http.postForEntity("/simulated-provider/refunds/" + refundRef + "/SUCCEEDED", new HttpEntity<>(new HttpHeaders()), String.class);
            assertThat(providerRefund.getStatusCode()).isEqualTo(HttpStatus.OK);
            callback("REFUND", order, refundRef, 100, "SUCCEEDED", "refund-event-" + dispute);
            returns.reconcileSuccessfulRefund(confirmedRefund);
            jdbc.update("UPDATE trade_order SET t0=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 8 DAY) WHERE id=?", order.toString());
            assertThat(settlements.settle(order).status()).isEqualTo("SETTLED");
            assertThat(jdbc.queryForObject("SELECT quarantined_quantity FROM listing WHERE id=?", Integer.class, listing.toString())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement WHERE order_id=?", Integer.class, order.toString())).isEqualTo(1);
            assertThat(http.postForEntity("/api/orders/" + order + "/reviews", entity("{\"rating\":5,\"content\":\"交付清晰\"}", buyerHeaders), String.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(http.postForEntity("/api/orders/" + order + "/reviews", entity("{\"rating\":4,\"content\":\"沟通顺畅\"}", sellerHeaders), String.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_order WHERE id=?", Integer.class, paymentId.toString())).isEqualTo(1);
        }
    }

    @Nested
    @Order(2)
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
        "spring.task.scheduling.enabled=false"
    })
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class WarrantyJourney extends WarrantyContainers {
        @AfterAll
        static void stopContainers() {
            stopWarrantyContainers();
        }

        @Test
        void keyboardWarrantyJourneyKeepsOrderSettledAndClearsSellerRestriction() throws Exception {
            User seller = register("warranty-seller-" + UUID.randomUUID() + "@stu.example.edu.cn");
            User buyer = register("warranty-buyer-" + UUID.randomUUID() + "@stu.example.edu.cn");
            User admin = seededAdmin();
            HttpHeaders sellerHeaders = bearer(seller.token());
            HttpHeaders buyerHeaders = bearer(buyer.token());
            ResponseEntity<String> created = http.postForEntity("/api/listings", entity("{\"title\":\"二手键盘\",\"description\":\"机械键盘\",\"category\":\"电子\",\"unitPriceFen\":500,\"availableQuantity\":1,\"sellerWarrantyDays\":90}", sellerHeaders), String.class);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            UUID listing = uuid(created.getBody(), "id");
            assertThat(uploadListingMedia(listing, seller).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(http.postForEntity("/api/listings/" + listing + "/publish", entity("", sellerHeaders), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
            ResponseEntity<String> orderResponse = http.postForEntity("/api/orders", entity("{\"listingId\":\"" + listing + "\",\"quantity\":1}", withKey(buyerHeaders, "w-order-" + listing)), String.class);
            assertThat(orderResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            UUID order = uuid(orderResponse.getBody(), "orderId");
            ResponseEntity<String> payment = http.postForEntity("/api/orders/" + order + "/payments", entity("{}", withKey(buyerHeaders, "w-pay-" + order)), String.class);
            assertThat(payment.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            String paymentRef = text(payment.getBody(), "providerReference");
            ResponseEntity<String> providerPayment = http.postForEntity("/simulated-provider/payments/" + paymentRef + "/SUCCEEDED", new HttpEntity<>(new HttpHeaders()), String.class);
            assertThat(providerPayment.getStatusCode()).isEqualTo(HttpStatus.OK);
            callback("PAYMENT", order, paymentRef, 500, "SUCCEEDED", "w-payment-event-" + order);
            assertThat(http.postForEntity("/api/orders/" + order + "/handoff", entity("{}", withKey(sellerHeaders, "w-handoff-" + order)), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(http.postForEntity("/api/orders/" + order + "/receipt", entity("{}", withKey(buyerHeaders, "w-receipt-" + order)), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
            jdbc.update("UPDATE trade_order SET t0=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 8 DAY) WHERE id=?", order.toString());
            assertThat(settlements.settle(order).status()).isEqualTo("SETTLED");
            jdbc.update("UPDATE trade_order SET t0=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 30 DAY) WHERE id=?", order.toString());
            ResponseEntity<String> opened = http.postForEntity("/api/orders/" + order + "/warranty", entity("{\"quantity\":1,\"reason\":\"FUNCTIONAL_DEFECT\"}", withKey(buyerHeaders, "w-case-" + order)), String.class);
            assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            UUID caseId = uuid(opened.getBody(), "caseId");
            assertThat(jdbc.queryForObject("SELECT warranty_days FROM trade_order WHERE id=?", Integer.class,
                order.toString())).isEqualTo(90);
            assertThat(jdbc.queryForObject("SELECT warranty_scope_snapshot FROM trade_order WHERE id=?", String.class,
                order.toString())).isEqualTo("SELLER_NON_HUMAN_FUNCTIONAL_FAILURE");
            LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("file", new ByteArrayResource("%PDF-1.4\nrepair quote\n%%EOF".getBytes(StandardCharsets.US_ASCII)) {
                @Override public String getFilename() { return "quote.pdf"; }
            });
            HttpHeaders evidenceHeaders = bearer(buyer.token());
            evidenceHeaders.set("X-Evidence-Purpose", "REPAIR_QUOTE");
            evidenceHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
            ResponseEntity<String> evidence = http.postForEntity("/api/warranty-cases/" + caseId + "/evidence", new HttpEntity<>(form, evidenceHeaders), String.class);
            assertThat(evidence.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            String evidenceId = text(evidence.getBody(), "evidenceId");
            assertThat(http.postForEntity("/api/warranty-cases/" + caseId + "/assignments", entity("{\"adminId\":\"" + admin.id() + "\"}", admin.headers()), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(http.postForEntity("/api/warranty-cases/" + caseId + "/decisions", entity("{\"decision\":\"REPAIR_COMPENSATION\",\"compensationAmountFen\":200,\"evidenceId\":\"" + evidenceId + "\"}", withKey(admin.headers(), "w-decision-" + caseId)), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
            ResponseEntity<String> restricted = http.exchange("/api/seller/restrictions", HttpMethod.GET, entity("", sellerHeaders), String.class);
            assertThat(restricted.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(restricted.getBody()).contains("\"publishRestricted\":true");
            UUID obligationId = UUID.fromString(jdbc.queryForObject("SELECT id FROM seller_obligation WHERE warranty_case_id=?", String.class, caseId.toString()));
            assertThat(jdbc.queryForObject("SELECT funding_deadline > DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 71 HOUR) FROM seller_obligation WHERE id=?", Boolean.class,
                obligationId.toString())).isTrue();
            jdbc.update("UPDATE seller_obligation SET funding_deadline=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", obligationId.toString());
            assertThat(http.postForEntity("/api/seller/obligations/" + obligationId + "/fund", entity("{\"amountFen\":200}", withKey(sellerHeaders, "fund-expired-" + obligationId)), String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            ResponseEntity<String> restrictedAfterExpiry = http.exchange("/api/seller/restrictions", HttpMethod.GET, entity("", sellerHeaders), String.class);
            assertThat(restrictedAfterExpiry.getBody()).contains("\"publishRestricted\":true", "\"withdrawRestricted\":true");
            ResponseEntity<String> blockedDraft = http.postForEntity("/api/listings", entity(
                "{\"title\":\"受限商品\",\"description\":\"不能发布\",\"category\":\"电子\",\"unitPriceFen\":10,\"availableQuantity\":1}", sellerHeaders), String.class);
            assertThat(blockedDraft.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            UUID blockedListing = uuid(blockedDraft.getBody(), "id");
            assertThat(uploadListingMedia(blockedListing, seller).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(http.postForEntity("/api/listings/" + blockedListing + "/publish", entity("", sellerHeaders), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(http.postForEntity("/api/seller/withdrawals", entity("{\"amountFen\":1}", withKey(sellerHeaders, "withdraw-expired-" + obligationId)), String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            UUID settlementId = UUID.nameUUIDFromBytes(("settlement:" + order).getBytes(StandardCharsets.UTF_8));
            assertThat(obligations.deductFutureSettlement(settlementId, obligationId, com.example.campusmarket.shared.Money.ofFen(200))).isEqualTo(200L);
            assertThat(obligations.deductFutureSettlement(settlementId, obligationId, com.example.campusmarket.shared.Money.ofFen(200))).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_obligation_deduction WHERE settlement_id=? AND obligation_id=?", Integer.class,
                settlementId.toString(), obligationId.toString())).isEqualTo(1);
            String compensationRef = jdbc.queryForObject("SELECT provider_reference FROM refund_order WHERE source_type='WARRANTY' AND source_id=?", String.class, caseId.toString());
            ResponseEntity<String> providerCompensation = http.postForEntity("/simulated-provider/refunds/" + compensationRef + "/SUCCEEDED", new HttpEntity<>(new HttpHeaders()), String.class);
            assertThat(providerCompensation.getStatusCode()).isEqualTo(HttpStatus.OK);
            callback("REFUND", order, compensationRef, 200, "SUCCEEDED", "w-refund-event-" + caseId);
            assertThat(jdbc.queryForObject("SELECT status FROM refund_order WHERE source_type='WARRANTY' AND source_id=?", String.class, caseId.toString())).isEqualTo("SUCCEEDED");
            ResponseEntity<String> unrestricted = http.exchange("/api/seller/restrictions", HttpMethod.GET, entity("", sellerHeaders), String.class);
            assertThat(unrestricted.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(unrestricted.getBody()).contains("\"publishRestricted\":false");
            assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("SETTLED");
            assertThat(obligations.findForSeller(seller.id())).isNotEmpty();
        }
    }
}
