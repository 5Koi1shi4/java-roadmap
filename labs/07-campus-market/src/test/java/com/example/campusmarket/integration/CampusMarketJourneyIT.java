package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.dispute.application.ProofAuthority;
import com.example.campusmarket.dispute.domain.DisputeDecision;
import com.example.campusmarket.dispute.domain.ReturnProofType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.ClassOrderer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
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
            // 本阶段不启动 MinIO；发布只需媒体元数据，内容读取由专门的存储 IT 验证。
            jdbc.update("INSERT INTO listing_media(id,listing_id,object_key,media_type,size_bytes,sort_order,created_at) VALUES (?,?,?,'image/png',1,0,CURRENT_TIMESTAMP(6))",
                UUID.randomUUID().toString(), listing.toString(), "fixture/textbook-" + listing + ".png");
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
            ResponseEntity<String> payment = http.postForEntity("/api/orders/" + order + "/payments", entity("{}", withKey(new HttpHeaders(), "w-pay-" + order)), String.class);
            String paymentRef = text(payment.getBody(), "providerReference");
            http.postForEntity("/simulated-provider/payments/" + paymentRef + "/SUCCEEDED", new HttpEntity<>(new HttpHeaders()), String.class);
            callback("PAYMENT", order, paymentRef, 500, "SUCCEEDED", "w-payment-event-" + order);
            http.postForEntity("/api/orders/" + order + "/handoff", entity("{}", withKey(sellerHeaders, "w-handoff-" + order)), String.class);
            http.postForEntity("/api/orders/" + order + "/receipt", entity("{}", withKey(buyerHeaders, "w-receipt-" + order)), String.class);
            jdbc.update("UPDATE trade_order SET t0=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 8 DAY) WHERE id=?", order.toString());
            assertThat(settlements.settle(order).status()).isEqualTo("SETTLED");
            jdbc.update("UPDATE trade_order SET t0=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 30 DAY) WHERE id=?", order.toString());
            ResponseEntity<String> opened = http.postForEntity("/api/orders/" + order + "/warranty", entity("{\"quantity\":1,\"reason\":\"FUNCTIONAL_DEFECT\"}", withKey(buyerHeaders, "w-case-" + order)), String.class);
            assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            UUID caseId = uuid(opened.getBody(), "caseId");
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
            http.postForEntity("/api/warranty-cases/" + caseId + "/assignments", entity("{\"adminId\":\"" + admin.id() + "\"}", admin.headers()), String.class);
            assertThat(http.postForEntity("/api/warranty-cases/" + caseId + "/decisions", entity("{\"decision\":\"REPAIR_COMPENSATION\",\"compensationAmountFen\":200,\"evidenceId\":\"" + evidenceId + "\"}", withKey(admin.headers(), "w-decision-" + caseId)), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(http.getForEntity("/api/seller/restrictions", String.class).getBody()).contains("\"publishRestricted\":true");
            UUID obligationId = UUID.fromString(jdbc.queryForObject("SELECT id FROM seller_obligation WHERE warranty_case_id=?", String.class, caseId.toString()));
            assertThat(http.postForEntity("/api/seller/obligations/" + obligationId + "/fund", entity("{\"amountFen\":200}", withKey(sellerHeaders, "fund-" + obligationId)), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(http.getForEntity("/api/seller/restrictions", String.class).getBody()).contains("\"publishRestricted\":false");
            assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("SETTLED");
            assertThat(obligations.findForSeller(seller.id())).isNotEmpty();
        }
    }
}
