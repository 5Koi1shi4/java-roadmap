package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.payment.application.PaymentGateway;
import com.example.campusmarket.payment.application.PaymentService;
import com.example.campusmarket.payment.application.RefundService;
import com.example.campusmarket.payment.infrastructure.JdbcPaymentRepository;
import com.example.campusmarket.payment.infrastructure.SimulatedPaymentProviderController;
import com.example.campusmarket.payment.application.PaymentReconciliationScheduler;
import com.example.campusmarket.identity.application.AuthenticatedUser;
import com.example.campusmarket.identity.infrastructure.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** 支付成功推进订单、退款额度预占和重复回调幂等的真实 MySQL 流程测试。 */
@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("local")
@TestPropertySource(properties = {"server.port=18081", "campus.market.payment.provider-url=http://localhost:18081/simulated-provider", "campus.market.payment.reconciliation.enabled=true"})
class PaymentFlowIT extends SharedContainers {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentService payments;
    @Autowired private RefundService refunds;
    @Autowired private JdbcPaymentRepository repository;
    @Autowired private PaymentReconciliationScheduler reconciliation;
    @Autowired private JwtService jwtService;
    @Autowired private SimulatedPaymentProviderController provider;

    @Test
    void paymentCallbackMovesPendingOrderToAwaitingHandoffOnlyOnce() throws Exception {
        UUID seller = user(); UUID buyer = user(); UUID listing = UUID.randomUUID(); UUID order = UUID.randomUUID(); UUID payment = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString(), "教材", "描述", "教材");
        jdbc.update("INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,0,'PENDING_PAYMENT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", order.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "描述");
        jdbc.update("INSERT INTO payment_order (id,order_id,provider,idempotency_key,amount_fen,status,created_at,updated_at) VALUES (?,?,?,?,100,'PENDING',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", payment.toString(), order.toString(), "simulated", "pay-" + payment);
        jdbc.update("UPDATE payment_order SET provider_reference='sim-pay-flow' WHERE id=?", payment.toString());
        String body = "{\"providerEventId\":\"evt-flow-" + payment + "\",\"type\":\"PAYMENT\",\"providerReference\":\"sim-pay-flow\",\"amountFen\":100,\"status\":\"SUCCEEDED\",\"occurredAt\":\"" + Instant.now() + "\"}";
        PaymentGateway.VerifiedCallback callback = new PaymentGateway.VerifiedCallback("simulated", "evt-flow-" + payment,
            PaymentGateway.VerifiedCallback.CallbackType.PAYMENT, "sim-pay-flow", 100, "SUCCEEDED", Instant.now(), UUID.randomUUID().toString());
        assertThat(payments.handleCallback(callback, body.getBytes(StandardCharsets.UTF_8)).firstSeen()).isTrue();
        assertThat(payments.handleCallback(callback, body.getBytes(StandardCharsets.UTF_8)).firstSeen()).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("AWAITING_HANDOFF");
    }

    @Test
    void concurrentRefundReservationIsBoundedByPaidAmount() {
        UUID seller = user(); UUID buyer = user(); UUID listing = UUID.randomUUID(); UUID order = UUID.randomUUID(); UUID payment = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString(), "教材", "描述", "教材");
        jdbc.update("INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,100,'AWAITING_HANDOFF',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", order.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "描述");
        jdbc.update("INSERT INTO payment_order (id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,status,created_at,updated_at) VALUES (?,?,?,?,100,100,'SUCCEEDED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", payment.toString(), order.toString(), "simulated", "pay-" + payment);
        assertThat(repository.reserveRefund(payment, 60)).isTrue();
        assertThat(repository.reserveRefund(payment, 50)).isFalse();
        assertThat(jdbc.queryForObject("SELECT reserved_refund_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(60L);
    }

    @Test
    void concurrentRefundReservationsNeverExceedPaidAmount() throws Exception {
        UUID payment = paidPayment(100);
        int workers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            results.add(executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return repository.reserveRefund(payment, 30);
            }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        long accepted = 0;
        for (var result : results) if (result.get(10, TimeUnit.SECONDS)) accepted++;
        executor.shutdownNow();
        assertThat(accepted).isLessThanOrEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT reserved_refund_fen FROM payment_order WHERE id=?", Long.class, payment.toString()))
            .isLessThanOrEqualTo(100L);
    }

    @Test
    void sameRefundIdempotencyKeyConcurrentlyClaimsOneReservationAndOneProviderRequest() throws Exception {
        provider.resetRequestCountersForTest();
        UUID payment = paidPayment(100);
        UUID order = UUID.fromString(jdbc.queryForObject("SELECT order_id FROM payment_order WHERE id=?", String.class, payment.toString()));
        jdbc.update("UPDATE payment_order SET provider_reference=? WHERE id=?", "sim-pay-paid-" + payment, payment.toString());
        String key = "same-refund-" + UUID.randomUUID();
        int workers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<RefundService.RefundResult>> results = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            results.add(executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return refunds.requestRefund(order, key, com.example.campusmarket.shared.Money.ofFen(30));
            }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        UUID refundId = null;
        for (var future : results) {
            RefundService.RefundResult result = future.get(15, TimeUnit.SECONDS);
            if (refundId == null) refundId = result.refundId();
            assertThat(result.refundId()).isEqualTo(refundId);
        }
        executor.shutdownNow();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_order WHERE order_id=? AND idempotency_key=?", Integer.class, order.toString(), key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reserved_refund_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(30L);
        assertThat(provider.refundCreateRequestCountForTest()).isEqualTo(1);
    }

    @Test
    void terminalRefundReconciliationSettlesReservationAndWritesOutbox() throws Exception {
        UUID payment = paidPayment(100);
        UUID order = UUID.fromString(jdbc.queryForObject("SELECT order_id FROM payment_order WHERE id=?", String.class, payment.toString()));
        jdbc.update("UPDATE payment_order SET provider_reference=? WHERE id=?", "sim-pay-reconcile-" + payment, payment.toString());
        RefundService.RefundResult requested = refunds.requestRefund(order, "reconcile-refund-" + payment, com.example.campusmarket.shared.Money.ofFen(30));
        HttpClient client = HttpClient.newHttpClient();
        client.send(HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/simulated-provider/refunds/" + requested.providerReference() + "/SUCCEEDED"))
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
        RefundService.RefundResult settled = refunds.reconcileRefund(requested.refundId());
        assertThat(settled.status()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT reserved_refund_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(0L);
        assertThat(jdbc.queryForObject("SELECT successful_refund_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(30L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='REFUND_SUCCEEDED' AND aggregate_id=?", Integer.class, requested.refundId().toString())).isEqualTo(1);
    }

    @Test
    void unknownRefundRetryNeverCreatesAgain() {
        provider.resetRequestCountersForTest();
        provider.setNextRefundStatusForTest("UNKNOWN");
        UUID payment = paidPayment(100);
        UUID order = UUID.fromString(jdbc.queryForObject("SELECT order_id FROM payment_order WHERE id=?", String.class, payment.toString()));
        jdbc.update("UPDATE payment_order SET provider_reference=? WHERE id=?", "sim-pay-unknown-refund-" + payment, payment.toString());

        String key = "unknown-refund-" + payment;
        RefundService.RefundResult first = refunds.requestRefund(order, key, com.example.campusmarket.shared.Money.ofFen(30));
        assertThat(first.status()).isEqualTo("UNKNOWN");
        jdbc.update("UPDATE refund_order SET next_reconcile_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),reconcile_lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", first.refundId().toString());
        reconciliation.runOnce(20);
        RefundService.RefundResult replay = refunds.requestRefund(order, key, com.example.campusmarket.shared.Money.ofFen(30));

        assertThat(replay.refundId()).isEqualTo(first.refundId());
        assertThat(provider.refundCreateRequestCount()).isEqualTo(1);
    }

    @Test
    void slowRefundCreateAndReconciliationDoNotDoubleCreate() throws Exception {
        provider.resetRequestCountersForTest();
        provider.setNextRefundStatusForTest("SUCCEEDED");
        provider.blockNextRefundCreateForTest();
        UUID payment = paidPayment(100);
        UUID order = UUID.fromString(jdbc.queryForObject("SELECT order_id FROM payment_order WHERE id=?", String.class, payment.toString()));
        jdbc.update("UPDATE payment_order SET provider_reference=? WHERE id=?", "sim-pay-slow-refund-" + payment, payment.toString());
        String key = "slow-refund-" + payment;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var request = executor.submit(() -> refunds.requestRefund(order, key, com.example.campusmarket.shared.Money.ofFen(30)));
        assertThat(provider.awaitRefundCreateEnteredForTest(10, TimeUnit.SECONDS)).isTrue();
        UUID refund = UUID.fromString(jdbc.queryForObject("SELECT id FROM refund_order WHERE order_id=? AND idempotency_key=?", String.class, order.toString(), key));
        jdbc.update("UPDATE refund_order SET next_reconcile_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),reconcile_lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", refund.toString());
        assertThat(reconciliation.runOnce(20)).isGreaterThanOrEqualTo(1);
        provider.releaseBlockedRefundCreateForTest();
        RefundService.RefundResult late = request.get(15, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(late.status()).isEqualTo("SUCCEEDED");
        assertThat(provider.refundCreateRequestCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='REFUND_SUCCEEDED' AND aggregate_id=?", Integer.class, refund.toString())).isEqualTo(1);
    }

    @Test
    void immediateRefundSuccessCommitsAtomically() {
        provider.setNextRefundStatusForTest("SUCCEEDED");
        UUID payment = paidPayment(100);
        UUID order = UUID.fromString(jdbc.queryForObject("SELECT order_id FROM payment_order WHERE id=?", String.class, payment.toString()));
        jdbc.update("UPDATE payment_order SET provider_reference=? WHERE id=?", "sim-pay-immediate-success-" + payment, payment.toString());

        RefundService.RefundResult result = refunds.requestRefund(order, "immediate-refund-success-" + payment, com.example.campusmarket.shared.Money.ofFen(30));
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.responseUtf8()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT reserved_refund_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(0L);
        assertThat(jdbc.queryForObject("SELECT successful_refund_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(30L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='REFUND_SUCCEEDED' AND aggregate_id=?", Integer.class, result.refundId().toString())).isEqualTo(1);
    }

    @Test
    void immediateRefundFailureReleasesAtomically() {
        provider.setNextRefundStatusForTest("FAILED");
        UUID payment = paidPayment(100);
        UUID order = UUID.fromString(jdbc.queryForObject("SELECT order_id FROM payment_order WHERE id=?", String.class, payment.toString()));
        jdbc.update("UPDATE payment_order SET provider_reference=? WHERE id=?", "sim-pay-immediate-failure-" + payment, payment.toString());

        RefundService.RefundResult result = refunds.requestRefund(order, "immediate-refund-failure-" + payment, com.example.campusmarket.shared.Money.ofFen(30));
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.responseUtf8()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT reserved_refund_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(0L);
        assertThat(jdbc.queryForObject("SELECT successful_refund_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(0L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='REFUND_FAILED' AND aggregate_id=?", Integer.class, result.refundId().toString())).isEqualTo(1);
    }

    @Test
    void immediateRefundSuccessAmountMismatchDoesNotSettle() {
        provider.setNextRefundStatusForTest("SUCCEEDED");
        provider.setNextRefundAmountFenForTest(29);
        UUID payment = paidPayment(100);
        UUID order = UUID.fromString(jdbc.queryForObject("SELECT order_id FROM payment_order WHERE id=?", String.class, payment.toString()));
        jdbc.update("UPDATE payment_order SET provider_reference=? WHERE id=?", "sim-pay-mismatch-success-" + payment, payment.toString());

        RefundService.RefundResult result = refunds.requestRefund(order, "immediate-refund-mismatch-success-" + payment, com.example.campusmarket.shared.Money.ofFen(30));
        assertThat(result.status()).isIn("REQUESTED", "PROCESSING", "UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT reserved_refund_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(30L);
        assertThat(jdbc.queryForObject("SELECT successful_refund_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(0L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE aggregate_id=? AND event_type LIKE 'REFUND_%'", Integer.class, result.refundId().toString())).isEqualTo(0);
    }

    @Test
    void immediateRefundFailureAmountMismatchDoesNotRelease() {
        provider.setNextRefundStatusForTest("FAILED");
        provider.setNextRefundAmountFenForTest(31);
        UUID payment = paidPayment(100);
        UUID order = UUID.fromString(jdbc.queryForObject("SELECT order_id FROM payment_order WHERE id=?", String.class, payment.toString()));
        jdbc.update("UPDATE payment_order SET provider_reference=? WHERE id=?", "sim-pay-mismatch-failure-" + payment, payment.toString());

        RefundService.RefundResult result = refunds.requestRefund(order, "immediate-refund-mismatch-failure-" + payment, com.example.campusmarket.shared.Money.ofFen(30));
        assertThat(result.status()).isIn("REQUESTED", "PROCESSING", "UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT reserved_refund_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(30L);
        assertThat(jdbc.queryForObject("SELECT successful_refund_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(0L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE aggregate_id=? AND event_type LIKE 'REFUND_%'", Integer.class, result.refundId().toString())).isEqualTo(0);
    }

    @Test
    void immediateRefundRollsBackWhenAggregateCasFails() throws Exception {
        provider.setNextRefundStatusForTest("SUCCEEDED");
        provider.blockNextRefundCreateForTest();
        UUID payment = paidPayment(100);
        UUID order = UUID.fromString(jdbc.queryForObject("SELECT order_id FROM payment_order WHERE id=?", String.class, payment.toString()));
        jdbc.update("UPDATE payment_order SET provider_reference=? WHERE id=?", "sim-pay-cas-refund-" + payment, payment.toString());
        String key = "immediate-refund-cas-" + payment;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var request = executor.submit(() -> refunds.requestRefund(order, key, com.example.campusmarket.shared.Money.ofFen(30)));
        assertThat(provider.awaitRefundCreateEnteredForTest(10, TimeUnit.SECONDS)).isTrue();
        UUID refund = UUID.fromString(jdbc.queryForObject("SELECT id FROM refund_order WHERE order_id=? AND idempotency_key=?", String.class, order.toString(), key));
        jdbc.update("UPDATE payment_order SET reserved_refund_fen=0 WHERE id=?", payment.toString());
        provider.releaseBlockedRefundCreateForTest();
        try {
            request.get(15, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException expected) {
            assertThat(expected.getCause())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("退款聚合结转失败，等待重试");
        }
        executor.shutdownNow();

        assertThat(jdbc.queryForObject("SELECT status FROM refund_order WHERE id=?", String.class, refund.toString())).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForObject("SELECT response_utf8 FROM refund_order WHERE id=?", byte[].class, refund.toString())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE aggregate_id=? AND event_type LIKE 'REFUND_%'", Integer.class, refund.toString())).isEqualTo(0);
    }

    @Test
    void pendingPaymentIsReconciledByRealSchedulerWithoutCreatingAnotherRequest() throws Exception {
        UUID payment = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID seller = user(); UUID buyer = user(); UUID listing = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString(), "教材", "描述", "教材");
        jdbc.update("INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,0,'PENDING_PAYMENT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", order.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "描述");
        String key = "scheduler-payment-" + payment;
        PaymentGateway.PaymentCreated created = new com.example.campusmarket.payment.infrastructure.SimulatedPaymentGateway(
            HttpClient.newHttpClient(), new com.fasterxml.jackson.databind.ObjectMapper(), "simulated", "http://localhost:" + port + "/simulated-provider", "local-only-payment-secret-change-me")
            .createPayment(new PaymentGateway.CreatePaymentRequest(order, com.example.campusmarket.shared.Money.ofFen(100), key));
        jdbc.update("INSERT INTO payment_order (id,order_id,provider,idempotency_key,amount_fen,status,provider_reference,next_reconcile_at,created_at,updated_at) VALUES (?,?,?,?,100,'PENDING',?,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", payment.toString(), order.toString(), "simulated", key, created.providerReference());
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/simulated-provider/payments/" + created.providerReference() + "/SUCCEEDED")).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(reconciliation.runOnce(20)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_order WHERE id=?", String.class, payment.toString())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("AWAITING_HANDOFF");
    }

    @Test
    void noReferencePendingPaymentReconcilesByProviderKeyAndBindsReturnedReference() throws Exception {
        UUID payment = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID seller = user(); UUID buyer = user(); UUID listing = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString(), "教材", "描述", "教材");
        jdbc.update("INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,0,'PENDING_PAYMENT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", order.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "描述");
        String key = "no-reference-" + payment;
        PaymentGateway.PaymentCreated created = new com.example.campusmarket.payment.infrastructure.SimulatedPaymentGateway(
            HttpClient.newHttpClient(), new com.fasterxml.jackson.databind.ObjectMapper(), "simulated", "http://localhost:" + port + "/simulated-provider", "local-only-payment-secret-change-me")
            .createPayment(new PaymentGateway.CreatePaymentRequest(order, com.example.campusmarket.shared.Money.ofFen(100), key));
        jdbc.update("INSERT INTO payment_order (id,order_id,provider,idempotency_key,amount_fen,status,next_reconcile_at,created_at,updated_at) VALUES (?,?,?,?,100,'UNKNOWN',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", payment.toString(), order.toString(), "simulated", key);
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/simulated-provider/payments/" + created.providerReference() + "/SUCCEEDED")).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(reconciliation.runOnce(20)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_order WHERE id=?", String.class, payment.toString())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT provider_reference FROM payment_order WHERE id=?", String.class, payment.toString())).isEqualTo(created.providerReference());
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("AWAITING_HANDOFF");
    }

    @Test
    void paymentReconciliationWrongAmountOrReferenceDoesNotAdvanceFunds() throws Exception {
        UUID seller = user(); UUID buyer = user(); UUID listing = UUID.randomUUID();
        UUID order = UUID.randomUUID(); UUID payment = UUID.randomUUID(); String key = "wrong-amount-" + payment;
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString(), "教材", "描述", "教材");
        jdbc.update("INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,99,0,'PENDING_PAYMENT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", order.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "描述");
        PaymentGateway.PaymentCreated created = new com.example.campusmarket.payment.infrastructure.SimulatedPaymentGateway(HttpClient.newHttpClient(), new com.fasterxml.jackson.databind.ObjectMapper(), "simulated", "http://localhost:" + port + "/simulated-provider", "local-only-payment-secret-change-me").createPayment(new PaymentGateway.CreatePaymentRequest(order, com.example.campusmarket.shared.Money.ofFen(100), key));
        jdbc.update("INSERT INTO payment_order (id,order_id,provider,idempotency_key,amount_fen,status,next_reconcile_at,created_at,updated_at) VALUES (?,?,?,?,99,'UNKNOWN',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", payment.toString(), order.toString(), "simulated", key);
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/simulated-provider/payments/" + created.providerReference() + "/SUCCEEDED")).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
        reconciliation.runOnce(20);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_order WHERE id=?", String.class, payment.toString())).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void refundReconciliationWrongReferenceOrAmountDoesNotAdvanceFunds() throws Exception {
        UUID payment = paidPayment(100);
        UUID order = UUID.fromString(jdbc.queryForObject("SELECT order_id FROM payment_order WHERE id=?", String.class, payment.toString()));
        jdbc.update("UPDATE payment_order SET provider_reference=? WHERE id=?", "sim-pay-refund-mismatch-" + payment, payment.toString());
        RefundService.RefundResult requested = refunds.requestRefund(order, "refund-mismatch-" + payment, com.example.campusmarket.shared.Money.ofFen(30));
        String originalReference = requested.providerReference();

        jdbc.update("UPDATE refund_order SET provider_reference='provider-reference-does-not-exist',next_reconcile_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),reconcile_lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", requested.refundId().toString());
        reconciliation.runOnce(20);
        assertThat(jdbc.queryForObject("SELECT status FROM refund_order WHERE id=?", String.class, requested.refundId().toString())).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT reserved_refund_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(30L);

        jdbc.update("UPDATE refund_order SET provider_reference=?,next_reconcile_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),reconcile_lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", originalReference, requested.refundId().toString());
        // The provider record is deliberately changed to a different amount via a second key.
        String otherKey = "refund-other-" + payment;
        new com.example.campusmarket.payment.infrastructure.SimulatedPaymentGateway(HttpClient.newHttpClient(), new com.fasterxml.jackson.databind.ObjectMapper(),
            "simulated", "http://localhost:" + port + "/simulated-provider", "local-only-payment-secret-change-me")
            .requestRefund(new PaymentGateway.CreateRefundRequest(order, "sim-pay-refund-mismatch-" + payment, com.example.campusmarket.shared.Money.ofFen(40), otherKey));
        String otherReference = "sim-refund-" + UUID.nameUUIDFromBytes(otherKey.getBytes(StandardCharsets.UTF_8));
        jdbc.update("UPDATE refund_order SET provider_reference=?,next_reconcile_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),reconcile_lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", otherReference, requested.refundId().toString());
        reconciliation.runOnce(20);
        assertThat(jdbc.queryForObject("SELECT status FROM refund_order WHERE id=?", String.class, requested.refundId().toString())).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT successful_refund_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(0L);
        assertThat(jdbc.queryForObject("SELECT reserved_refund_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(30L);
    }

    @Test
    void immediateProviderSuccessAndFailureSettlePaymentAndOrderAtomically() {
        provider.setNextPaymentStatusForTest("SUCCEEDED");
        UUID successOrder = pendingOrder();
        String successKey = "immediate-success-" + successOrder;
        byte[] requestBody = "{}".getBytes(StandardCharsets.UTF_8);
        PaymentService.PaymentResult success = payments.createPayment(successOrder, successKey, requestBody);
        assertThat(success.status()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT paid_amount_fen FROM payment_order WHERE id=?", Long.class, success.paymentId().toString())).isEqualTo(100L);
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, successOrder.toString())).isEqualTo("AWAITING_HANDOFF");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='PAYMENT_SUCCEEDED' AND aggregate_id=?", Integer.class, success.paymentId().toString())).isEqualTo(1);
        PaymentService.PaymentResult replay = payments.createPayment(successOrder, successKey, requestBody);
        assertThat(replay.status()).isEqualTo("SUCCEEDED");
        assertThat(replay.responseUtf8()).containsExactly(success.responseUtf8());

        provider.setNextPaymentStatusForTest("FAILED");
        UUID failedOrder = pendingOrder();
        PaymentService.PaymentResult failed = payments.createPayment(failedOrder, "immediate-failed-" + failedOrder, "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT paid_amount_fen FROM payment_order WHERE id=?", Long.class, failed.paymentId().toString())).isEqualTo(0L);
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, failedOrder.toString())).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='PAYMENT_FAILED' AND aggregate_id=?", Integer.class, failed.paymentId().toString())).isEqualTo(1);
    }

    @Test
    void unknownPaymentRetryNeverCreatesAgain() throws Exception {
        provider.resetRequestCountersForTest();
        UUID order = pendingOrder();
        String key = "unknown-retry-" + order;
        provider.blockNextPaymentCreateForTest();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var first = executor.submit(() -> payments.createPayment(order, key, "{}".getBytes(StandardCharsets.UTF_8)));
        try {
            assertThat(provider.awaitPaymentCreateEnteredForTest(10, TimeUnit.SECONDS)).isTrue();
            PaymentService.PaymentResult unknown = first.get(20, TimeUnit.SECONDS);
            assertThat(unknown.status()).isEqualTo("UNKNOWN");
            assertThat(provider.paymentCreateRequestCountForTest()).isEqualTo(1);

            String paymentId = unknown.paymentId().toString();
            String reference = "sim-pay-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
            client().send(HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/simulated-provider/payments/" + reference + "/SUCCEEDED"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
            jdbc.update("UPDATE payment_order SET next_reconcile_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),reconcile_lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", paymentId);

            PaymentService.PaymentResult retry = payments.createPayment(order, key, "{}".getBytes(StandardCharsets.UTF_8));
            assertThat(retry.status()).isEqualTo("UNKNOWN");
            reconciliation.runOne(UUID.fromString(paymentId));
            assertThat(provider.paymentCreateRequestCountForTest()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM payment_order WHERE id=?", String.class, paymentId)).isEqualTo("SUCCEEDED");
        } finally {
            provider.releaseBlockedPaymentCreateForTest();
            executor.shutdownNow();
        }
    }

    @Test
    void lateProviderSuccessAfterPaymentTimeoutIsRecordedAndCompensatedExactlyOnce() {
        UUID order = pendingOrder();
        UUID payment = UUID.randomUUID();
        String owner = "late-owner-" + payment;
        String token = "late-token-" + payment;
        String reference = "late-reference-" + payment;
        jdbc.update("INSERT INTO payment_order (id,order_id,provider,idempotency_key,amount_fen,status,reconcile_owner,reconcile_token,reconcile_lease_until,created_at,updated_at) VALUES (?,?,?,?,100,'UNKNOWN',?,?,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 30 SECOND),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            payment.toString(), order.toString(), "simulated", "late-payment-" + payment, owner, token);
        jdbc.update("UPDATE trade_order SET status='CANCELLED',version=version+1 WHERE id=?", order.toString());

        UUID refund = repository.recordLatePaymentSuccessAfterCancellation(payment, order, reference, 100, owner, token);
        assertThat(refund).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM payment_order WHERE id=?", String.class, payment.toString())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT paid_amount_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(100L);
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM refund_order WHERE id=?", String.class, refund.toString())).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForObject("SELECT reserved_refund_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(100L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='REFUND_REQUESTED' AND aggregate_id=?", Integer.class, refund.toString())).isEqualTo(1);
        assertThat(repository.recordLatePaymentSuccessAfterCancellation(payment, order, reference, 100, owner, token)).isEqualTo(refund);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='REFUND_REQUESTED' AND aggregate_id=?", Integer.class, refund.toString())).isEqualTo(1);
    }

    @Test
    void lateProviderSuccessThroughReconciliationStaysCancelledAndCompensatesOnce() throws Exception {
        provider.resetRequestCountersForTest();
        UUID order = pendingOrder();
        String key = "late-reconcile-" + order;
        provider.blockNextPaymentCreateForTest();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var request = executor.submit(() -> payments.createPayment(order, key, "{}".getBytes(StandardCharsets.UTF_8)));
            assertThat(provider.awaitPaymentCreateEnteredForTest(10, TimeUnit.SECONDS)).isTrue();
            PaymentService.PaymentResult unknown = request.get(20, TimeUnit.SECONDS);
            assertThat(unknown.status()).isEqualTo("UNKNOWN");
            String reference = "sim-pay-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
            client().send(HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/simulated-provider/payments/" + reference + "/SUCCEEDED"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
            String paymentId = unknown.paymentId().toString();
            String listingId = jdbc.queryForObject("SELECT listing_id FROM trade_order WHERE id=?", String.class, order.toString());
            jdbc.update("UPDATE trade_order SET status='CANCELLED',version=version+1 WHERE id=?", order.toString());
            jdbc.update("UPDATE payment_order SET next_reconcile_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),reconcile_lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", paymentId);

            assertThat(reconciliation.runOne(unknown.paymentId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("CANCELLED");
            assertThat(jdbc.queryForObject("SELECT status FROM payment_order WHERE id=?", String.class, paymentId)).isEqualTo("SUCCEEDED");
            assertThat(jdbc.queryForObject("SELECT available_quantity FROM listing WHERE id=?", Integer.class, listingId)).isZero();
            String refundId = jdbc.queryForObject("SELECT id FROM refund_order WHERE order_id=?", String.class, order.toString());
            assertThat(jdbc.queryForObject("SELECT status FROM refund_order WHERE id=?", String.class, refundId)).isEqualTo("REQUESTED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='REFUND_REQUESTED' AND aggregate_id=?", Integer.class, refundId)).isEqualTo(1);
            assertThat(reconciliation.runOne(unknown.paymentId())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_order WHERE order_id=?", Integer.class, order.toString())).isEqualTo(1);
        } finally {
            provider.releaseBlockedPaymentCreateForTest();
            executor.shutdownNow();
        }
    }

    @Test
    void immediatePaymentSuccessRollsBackWhenOrderCasFails() throws Exception {
        provider.resetRequestCountersForTest();
        provider.setNextPaymentStatusForTest("SUCCEEDED");
        UUID order = pendingOrder();
        String key = "payment-order-cas-" + order;
        provider.blockNextPaymentCreateForTest();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var request = executor.submit(() -> payments.createPayment(order, key, "{}".getBytes(StandardCharsets.UTF_8)));
        try {
            assertThat(provider.awaitPaymentCreateEnteredForTest(10, TimeUnit.SECONDS)).isTrue();
            String paymentId = jdbc.queryForObject("SELECT id FROM payment_order WHERE order_id=?", String.class, order.toString());
            jdbc.update("UPDATE trade_order SET status='CANCELLED' WHERE id=?", order.toString());
            provider.releaseBlockedPaymentCreateForTest();
            assertThat(request.get(15, TimeUnit.SECONDS).status()).isNotEqualTo("SUCCEEDED");
            assertThat(jdbc.queryForObject("SELECT status FROM payment_order WHERE id=?", String.class, paymentId)).isNotEqualTo("SUCCEEDED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='PAYMENT_SUCCEEDED' AND aggregate_id=?", Integer.class, paymentId)).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isNotEqualTo("AWAITING_HANDOFF");
        } finally {
            provider.releaseBlockedPaymentCreateForTest();
            executor.shutdownNow();
        }
    }

    @Test
    void specialJsonEscapesArePartOfPaymentRequestFingerprint() throws Exception {
        UUID order = pendingOrder();
        UUID userId = UUID.fromString(jdbc.queryForObject("SELECT buyer_id FROM trade_order WHERE id=?", String.class, order.toString()));
        String bearer = "Bearer " + jwtService.issue(new AuthenticatedUser(userId, Set.of("ROLE_USER")));
        String key = "special-json-" + order;
        String firstBody = "{\"note\":\"引号\\\" 反斜杠\\\\ 雪☃\"}";
        String escapedBody = "{\"note\":\"引号\\\" 反斜杠\\\\ 雪\\u2603\"}";
        HttpClient client = HttpClient.newHttpClient();
        java.net.URI uri = java.net.URI.create("http://localhost:" + port + "/api/orders/" + order + "/payments");
        HttpResponse<byte[]> first = client.send(HttpRequest.newBuilder(uri).header("Authorization", bearer).header("Idempotency-Key", key)
            .header("Content-Type", "application/json; charset=UTF-8").POST(HttpRequest.BodyPublishers.ofString(firstBody, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> replay = client.send(HttpRequest.newBuilder(uri).header("Authorization", bearer).header("Idempotency-Key", key)
            .header("Content-Type", "application/json; charset=UTF-8").POST(HttpRequest.BodyPublishers.ofString(firstBody, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> conflict = client.send(HttpRequest.newBuilder(uri).header("Authorization", bearer).header("Idempotency-Key", key)
            .header("Content-Type", "application/json; charset=UTF-8").POST(HttpRequest.BodyPublishers.ofString(escapedBody, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(first.statusCode());
        assertThat(replay.body()).containsExactly(first.body());
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(new String(conflict.body(), StandardCharsets.UTF_8)).contains("幂等");
    }

    @Test
    void concurrentPaymentIdempotencyPerformsOneRealProviderRequest() throws Exception {
        provider.resetRequestCountersForTest();
        UUID order = pendingOrder();
        String key = "payment-count-" + order;
        int workers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<PaymentService.PaymentResult>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) futures.add(executor.submit(() -> {
            ready.countDown(); start.await(5, TimeUnit.SECONDS);
            return payments.createPayment(order, key, "{}".getBytes(StandardCharsets.UTF_8));
        }));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (var future : futures) assertThat(future.get(15, TimeUnit.SECONDS).paymentId()).isNotNull();
        executor.shutdownNow();
        assertThat(provider.paymentCreateRequestCountForTest()).isEqualTo(1);
    }

    @Test
    void initialPaymentRequestCannotOverwriteSchedulerTerminalResponseAfterLeaseHandoff() throws Exception {
        provider.resetRequestCountersForTest();
        UUID order = pendingOrder();
        String key = "payment-race-" + order;
        var gateway = new com.example.campusmarket.payment.infrastructure.SimulatedPaymentGateway(HttpClient.newHttpClient(), new com.fasterxml.jackson.databind.ObjectMapper(),
            "simulated", "http://localhost:" + port + "/simulated-provider", "local-only-payment-secret-change-me");
        gateway.createPayment(new PaymentGateway.CreatePaymentRequest(order, com.example.campusmarket.shared.Money.ofFen(100), key));
        provider.blockNextPaymentCreateForTest();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var future = executor.submit(() -> payments.createPayment(order, key, "{}".getBytes(StandardCharsets.UTF_8)));
        try {
            assertThat(provider.awaitPaymentCreateEnteredForTest(10, TimeUnit.SECONDS)).isTrue();
            String paymentId = jdbc.queryForObject("SELECT id FROM payment_order WHERE order_id=?", String.class, order.toString());
            jdbc.update("UPDATE payment_order SET next_reconcile_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),reconcile_lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", paymentId);
            String reference = "sim-pay-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
            client().send(HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/simulated-provider/payments/" + reference + "/SUCCEEDED"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(reconciliation.runOnce(20)).isGreaterThanOrEqualTo(1);
            byte[] terminal = jdbc.queryForObject("SELECT response_utf8 FROM payment_order WHERE id=?", (rs, rowNum) -> rs.next() ? rs.getBytes(1) : null, paymentId);
            assertThat(jdbc.queryForObject("SELECT status FROM payment_order WHERE id=?", String.class, paymentId)).isEqualTo("SUCCEEDED");
            provider.releaseBlockedPaymentCreateForTest();
            PaymentService.PaymentResult late = future.get(15, TimeUnit.SECONDS);
            assertThat(late.status()).isEqualTo("SUCCEEDED");
            assertThat((byte[]) jdbc.queryForObject("SELECT response_utf8 FROM payment_order WHERE id=?", (rs, rowNum) -> rs.next() ? rs.getBytes(1) : null, paymentId)).containsExactly(terminal);
        } finally {
            provider.releaseBlockedPaymentCreateForTest();
            executor.shutdownNow();
        }
    }

    @Test
    void refundCallbackAggregateCasFailureRollsBackRefundCallbackAndOutbox() {
        UUID payment = paidPayment(100);
        UUID order = UUID.fromString(jdbc.queryForObject("SELECT order_id FROM payment_order WHERE id=?", String.class, payment.toString()));
        UUID refund = UUID.randomUUID();
        jdbc.update("INSERT INTO refund_order (id,order_id,payment_order_id,provider,idempotency_key,source_type,paid_amount_fen,amount_fen,reserved_refund_fen,provider_reference,status,created_at,updated_at) VALUES (?,?,?,?,?,'ORDER',?,?,0,?,'REQUESTED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", refund.toString(), order.toString(), payment.toString(), "simulated", "callback-cas-" + refund, 100, 40, "callback-ref-" + refund);
        PaymentGateway.VerifiedCallback callback = new PaymentGateway.VerifiedCallback("simulated", "callback-cas-event-" + refund,
            PaymentGateway.VerifiedCallback.CallbackType.REFUND, "callback-ref-" + refund, 40, "SUCCEEDED", Instant.now(), "callback-cas-nonce-" + refund);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> refunds.handleCallback(callback, "{\"cas\":true}".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM refund_order WHERE id=?", String.class, refund.toString())).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForObject("SELECT reserved_refund_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(0L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_callback_event WHERE provider_event_id=?", Integer.class, callback.providerEventId())).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE aggregate_id=?", Integer.class, refund.toString())).isEqualTo(0);
    }

    @Test
    void failedRefundCallbackAggregateCasFailureAlsoRollsBack() {
        UUID payment = paidPayment(100);
        UUID order = UUID.fromString(jdbc.queryForObject("SELECT order_id FROM payment_order WHERE id=?", String.class, payment.toString()));
        UUID refund = UUID.randomUUID();
        jdbc.update("INSERT INTO refund_order (id,order_id,payment_order_id,provider,idempotency_key,source_type,paid_amount_fen,amount_fen,reserved_refund_fen,provider_reference,status,created_at,updated_at) VALUES (?,?,?,?,?,'ORDER',?,?,0,?,'REQUESTED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", refund.toString(), order.toString(), payment.toString(), "simulated", "callback-failed-cas-" + refund, 100, 40, "callback-failed-ref-" + refund);
        PaymentGateway.VerifiedCallback callback = new PaymentGateway.VerifiedCallback("simulated", "callback-failed-cas-event-" + refund,
            PaymentGateway.VerifiedCallback.CallbackType.REFUND, "callback-failed-ref-" + refund, 40, "FAILED", Instant.now(), "callback-failed-cas-nonce-" + refund);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> refunds.handleCallback(callback, "{\"cas\":\"failed\"}".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM refund_order WHERE id=?", String.class, refund.toString())).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_callback_event WHERE provider_event_id=?", Integer.class, callback.providerEventId())).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE aggregate_id=?", Integer.class, refund.toString())).isEqualTo(0);
    }

    @Test
    void paymentAndRefundApisRejectMissingBodyAndBlankKeyAsUtf8BadRequest() throws Exception {
        UUID userId = user();
        String bearer = "Bearer " + jwtService.issue(new AuthenticatedUser(userId, Set.of("ROLE_USER")));
        UUID order = UUID.randomUUID(); UUID seller = user(); UUID listing = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString(), "教材", "描述", "教材");
        jdbc.update("INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,0,'PENDING_PAYMENT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", order.toString(), userId.toString(), seller.toString(), listing.toString(), "教材", "描述");
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<byte[]> missing = client.send(HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api/orders/" + order + "/payments"))
            .header("Authorization", bearer).header("Idempotency-Key", "missing-body-" + order).header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(missing.statusCode()).isEqualTo(400);
        String missingContentType = missing.headers().firstValue("Content-Type").orElseThrow();
        assertThat(missingContentType).contains("application/json").containsIgnoringCase("charset=UTF-8");
        HttpResponse<byte[]> blank = client.send(HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api/orders/" + order + "/payments"))
            .header("Authorization", bearer).header("Idempotency-Key", "   ").header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(blank.statusCode()).isEqualTo(400);
        assertThat(new String(blank.body(), StandardCharsets.UTF_8)).contains("请求参数无效");
        HttpResponse<byte[]> refundMissing = client.send(HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api/refunds"))
            .header("Authorization", bearer).header("Idempotency-Key", "refund-missing-" + order).header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(refundMissing.statusCode()).isEqualTo(400);
    }

    @Test
    void paymentAndRefundApisReplayPersistedUtf8BytesAndRejectDifferentRefundBody() throws Exception {
        UUID userId = user();
        String bearer = "Bearer " + jwtService.issue(new AuthenticatedUser(userId, Set.of("ROLE_USER")));
        UUID order = UUID.randomUUID();
        UUID seller = user(); UUID listing = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString(), "教材", "描述", "教材");
        jdbc.update("INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,0,'PENDING_PAYMENT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", order.toString(), userId.toString(), seller.toString(), listing.toString(), "教材", "描述");
        String paymentKey = "api-payment-replay-" + UUID.randomUUID();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest paymentRequest = HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api/orders/" + order + "/payments"))
            .header("Authorization", bearer).header("Idempotency-Key", paymentKey).header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8)).build();
        HttpResponse<byte[]> firstPayment = client.send(paymentRequest, HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> replayPayment = client.send(HttpRequest.newBuilder(paymentRequest.uri()).header("Authorization", bearer).header("Idempotency-Key", paymentKey).header("Content-Type", "application/json; charset=UTF-8").POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> conflictPayment = client.send(HttpRequest.newBuilder(paymentRequest.uri()).header("Authorization", bearer).header("Idempotency-Key", paymentKey).header("Content-Type", "application/json; charset=UTF-8").POST(HttpRequest.BodyPublishers.ofString("{\"amountFen\":101}", StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(firstPayment.statusCode()).isEqualTo(201);
        assertThat(replayPayment.statusCode()).isEqualTo(firstPayment.statusCode());
        assertThat(replayPayment.body()).containsExactly(firstPayment.body());
        assertThat(replayPayment.headers().firstValue("Content-Type")).isEqualTo(firstPayment.headers().firstValue("Content-Type"));
        assertThat(conflictPayment.statusCode()).isEqualTo(409);
        assertThat(new String(conflictPayment.body(), StandardCharsets.UTF_8)).contains("幂等");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_order WHERE order_id=?", Integer.class, order.toString())).isEqualTo(1);

        UUID paidPayment = paidPayment(100);
        UUID refundOrder = UUID.fromString(jdbc.queryForObject("SELECT order_id FROM payment_order WHERE id=?", String.class, paidPayment.toString()));
        jdbc.update("UPDATE payment_order SET provider_reference=? WHERE id=?", "sim-pay-api-refund-" + paidPayment, paidPayment.toString());
        String refundKey = "api-refund-replay-" + UUID.randomUUID();
        String refundUri = "http://localhost:" + port + "/api/refunds";
        HttpRequest refundRequest = HttpRequest.newBuilder(java.net.URI.create(refundUri)).header("Authorization", bearer).header("Idempotency-Key", refundKey).header("Content-Type", "application/json; charset=UTF-8").POST(HttpRequest.BodyPublishers.ofString("{\"orderId\":\"" + refundOrder + "\",\"amountFen\":30}", StandardCharsets.UTF_8)).build();
        HttpResponse<byte[]> firstRefund = client.send(refundRequest, HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> replayRefund = client.send(HttpRequest.newBuilder(java.net.URI.create(refundUri)).header("Authorization", bearer).header("Idempotency-Key", refundKey).header("Content-Type", "application/json; charset=UTF-8").POST(HttpRequest.BodyPublishers.ofString("{\"orderId\":\"" + refundOrder + "\",\"amountFen\":30}", StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> conflictRefund = client.send(HttpRequest.newBuilder(java.net.URI.create(refundUri)).header("Authorization", bearer).header("Idempotency-Key", refundKey).header("Content-Type", "application/json; charset=UTF-8").POST(HttpRequest.BodyPublishers.ofString("{\"amountFen\":30,\"orderId\":\"" + refundOrder + "\"}", StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(firstRefund.statusCode()).isEqualTo(201);
        assertThat(replayRefund.statusCode()).isEqualTo(firstRefund.statusCode());
        assertThat(replayRefund.body()).containsExactly(firstRefund.body());
        assertThat(replayRefund.headers().firstValue("Content-Type")).isEqualTo(firstRefund.headers().firstValue("Content-Type"));
        assertThat(conflictRefund.statusCode()).isEqualTo(409);
        assertThat(new String(conflictRefund.body(), StandardCharsets.UTF_8)).contains("幂等");
    }

    @Test
    void independentDatabaseOwnersFenceStalePaymentAndRefundClaims() throws Exception {
        UUID payment = paidPayment(100);
        UUID order = UUID.fromString(jdbc.queryForObject("SELECT order_id FROM payment_order WHERE id=?", String.class, payment.toString()));
        jdbc.update("UPDATE payment_order SET status='UNKNOWN',provider_reference='fence-pay',next_reconcile_at=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 HOUR) WHERE id=?", payment.toString());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        var a = workers.submit(() -> repository.claimPaymentReconciliationDirect(payment, "worker-a", "token-a"));
        var b = workers.submit(() -> repository.claimPaymentReconciliationDirect(payment, "worker-b", "token-b"));
        boolean first = a.get(10, TimeUnit.SECONDS); boolean second = b.get(10, TimeUnit.SECONDS);
        assertThat(first ^ second).isTrue();
        jdbc.update("UPDATE payment_order SET reconcile_lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND)");
        assertThat(repository.claimPaymentReconciliation(payment, "worker-b", "token-b")).isTrue();
        assertThat(repository.markPaymentSucceeded(payment, "fence-pay", "fence-pay", 100, "worker-a", "token-a")).isFalse();
        assertThat(repository.markPaymentSucceeded(payment, "fence-pay", "fence-pay", 100, "worker-b", "token-b")).isTrue();

        UUID refund = UUID.randomUUID();
        jdbc.update("UPDATE payment_order SET reserved_refund_fen=30 WHERE id=?", payment.toString());
        jdbc.update("INSERT INTO refund_order (id,order_id,payment_order_id,provider,idempotency_key,source_type,paid_amount_fen,amount_fen,reserved_refund_fen,provider_reference,status,next_reconcile_at,created_at,updated_at) VALUES (?,?,?,?,?,'ORDER',?,?,?,'fence-ref','PROCESSING',DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 HOUR),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", refund.toString(), order.toString(), payment.toString(), "simulated", "fence-refund", 100, 30, 30);
        var ra = workers.submit(() -> repository.claimRefundReconciliationDirect(refund, "worker-a", "refund-token-a"));
        var rb = workers.submit(() -> repository.claimRefundReconciliationDirect(refund, "worker-b", "refund-token-b"));
        boolean refundFirst = ra.get(10, TimeUnit.SECONDS); boolean refundSecond = rb.get(10, TimeUnit.SECONDS);
        assertThat(refundFirst ^ refundSecond).isTrue();
        jdbc.update("UPDATE refund_order SET reconcile_lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", refund.toString());
        assertThat(repository.claimRefundReconciliation(refund, "worker-b", "refund-token-b")).isTrue();
        assertThat(repository.markRefundTerminal(refund, "fence-ref", "fence-ref", "SUCCEEDED", 30, "worker-a", "refund-token-a")).isFalse();
        assertThat(repository.markRefundTerminal(refund, "fence-ref", "fence-ref", "SUCCEEDED", 30, "worker-b", "refund-token-b")).isTrue();
        workers.shutdownNow();
        assertThat(jdbc.queryForObject("SELECT status FROM refund_order WHERE id=?", String.class, refund.toString())).isEqualTo("SUCCEEDED");
    }

    @Test
    void refundCallbackMismatchAndOutOfOrderEventsCannotConsumeAnotherRefundReservation() {
        UUID payment = paidPayment(100);
        UUID order = UUID.fromString(jdbc.queryForObject("SELECT order_id FROM payment_order WHERE id=?", String.class, payment.toString()));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        jdbc.update("INSERT INTO refund_order (id,order_id,payment_order_id,provider,idempotency_key,source_type,paid_amount_fen,amount_fen,reserved_refund_fen,status,created_at,updated_at) VALUES (?,?,?,?,?,'ORDER',?,?,?, 'REQUESTED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            first.toString(), order.toString(), payment.toString(), "simulated", "refund-" + first, 100, 40, 40);
        jdbc.update("INSERT INTO refund_order (id,order_id,payment_order_id,provider,idempotency_key,source_type,paid_amount_fen,amount_fen,reserved_refund_fen,status,created_at,updated_at) VALUES (?,?,?,?,?,'ORDER',?,?,?, 'REQUESTED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            second.toString(), order.toString(), payment.toString(), "simulated", "refund-" + second, 100, 40, 40);
        jdbc.update("UPDATE refund_order SET provider_reference='provider-first' WHERE id=?", first.toString());
        jdbc.update("UPDATE payment_order SET reserved_refund_fen=80 WHERE id=?", payment.toString());
        PaymentGateway.VerifiedCallback wrongAmount = new PaymentGateway.VerifiedCallback("simulated", "event-wrong-" + first,
            PaymentGateway.VerifiedCallback.CallbackType.REFUND, "provider-first", 20, "SUCCEEDED", Instant.now(), UUID.randomUUID().toString());
        refunds.handleCallback(wrongAmount, "{\"wrong\":true}".getBytes(StandardCharsets.UTF_8));
        assertThat(jdbc.queryForObject("SELECT reserved_refund_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(80L);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_callback_event WHERE provider_event_id=?", String.class, "event-wrong-" + first)).isEqualTo("FAILED");
        PaymentGateway.VerifiedCallback wrongReference = new PaymentGateway.VerifiedCallback("simulated", "event-unknown-reference-" + first,
            PaymentGateway.VerifiedCallback.CallbackType.REFUND, "provider-not-linked", 40, "SUCCEEDED", Instant.now(), UUID.randomUUID().toString());
        refunds.handleCallback(wrongReference, "{\"unknownReference\":true}".getBytes(StandardCharsets.UTF_8));
        assertThat(jdbc.queryForObject("SELECT reserved_refund_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(80L);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_callback_event WHERE provider_event_id=?", String.class, "event-unknown-reference-" + first)).isEqualTo("FAILED");
        jdbc.update("UPDATE refund_order SET provider_reference='provider-second' WHERE id=?", second.toString());
        PaymentGateway.VerifiedCallback secondSuccess = new PaymentGateway.VerifiedCallback("simulated", "event-second-" + second,
            PaymentGateway.VerifiedCallback.CallbackType.REFUND, "provider-second", 40, "SUCCEEDED", Instant.now(), UUID.randomUUID().toString());
        refunds.handleCallback(secondSuccess, "{\"second\":true}".getBytes(StandardCharsets.UTF_8));
        assertThat(jdbc.queryForObject("SELECT reserved_refund_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(40L);
        assertThat(jdbc.queryForObject("SELECT status FROM refund_order WHERE id=?", String.class, first.toString())).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForObject("SELECT status FROM refund_order WHERE id=?", String.class, second.toString())).isEqualTo("SUCCEEDED");
    }

    @Test
    void paymentCreateAttemptCanOnlyBeClaimedOnce() {
        UUID order = pendingOrder();
        UUID payment = UUID.randomUUID();
        jdbc.update("INSERT INTO payment_order (id,order_id,provider,idempotency_key,amount_fen,status,created_at,updated_at) VALUES (?,?,?,?,100,'PENDING',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            payment.toString(), order.toString(), "simulated", "initial-payment-" + payment);

        assertThat(repository.claimInitialPaymentAttempt(payment, "owner-a", "token-a")).isTrue();
        jdbc.update("UPDATE payment_order SET reconcile_lease_until=DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE id=?", payment.toString());
        assertThat(repository.claimInitialPaymentAttempt(payment, "owner-b", "token-b")).isFalse();
        assertThat(jdbc.queryForObject("SELECT create_attempted_at IS NOT NULL FROM payment_order WHERE id=?", Boolean.class, payment.toString())).isTrue();
        assertThat(jdbc.queryForObject("SELECT reconcile_token FROM payment_order WHERE id=?", String.class, payment.toString())).isEqualTo("token-a");
    }

    @Test
    void refundCreateAttemptCanOnlyBeClaimedOnce() {
        UUID payment = paidPayment(100);
        UUID order = UUID.fromString(jdbc.queryForObject("SELECT order_id FROM payment_order WHERE id=?", String.class, payment.toString()));
        UUID refund = UUID.randomUUID();
        jdbc.update("INSERT INTO refund_order (id,order_id,payment_order_id,provider,idempotency_key,source_type,paid_amount_fen,amount_fen,reserved_refund_fen,status,created_at,updated_at) VALUES (?,?,?,?,?,'ORDER',?,?,?,'REQUESTED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            refund.toString(), order.toString(), payment.toString(), "simulated", "initial-refund-" + refund, 100, 40, 40);

        assertThat(repository.claimInitialRefundAttempt(refund, "owner-a", "token-a")).isTrue();
        jdbc.update("UPDATE refund_order SET reconcile_lease_until=DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE id=?", refund.toString());
        assertThat(repository.claimInitialRefundAttempt(refund, "owner-b", "token-b")).isFalse();
        assertThat(jdbc.queryForObject("SELECT create_attempted_at IS NOT NULL FROM refund_order WHERE id=?", Boolean.class, refund.toString())).isTrue();
        assertThat(jdbc.queryForObject("SELECT reconcile_token FROM refund_order WHERE id=?", String.class, refund.toString())).isEqualTo("token-a");
    }

    @Test
    void legacyRefundCreateClaimRetainsResultFencing() {
        UUID payment = paidPayment(100);
        UUID order = UUID.fromString(jdbc.queryForObject("SELECT order_id FROM payment_order WHERE id=?", String.class, payment.toString()));
        UUID refund = UUID.randomUUID();
        jdbc.update("INSERT INTO refund_order (id,order_id,payment_order_id,provider,idempotency_key,source_type,paid_amount_fen,amount_fen,reserved_refund_fen,status,created_at,updated_at) VALUES (?,?,?,?,?,'ORDER',?,?,?,'REQUESTED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            refund.toString(), order.toString(), payment.toString(), "simulated", "legacy-refund-claim-" + refund, 100, 40, 40);

        assertThat(repository.claimRefundRequest(refund)).isTrue();
        assertThat(jdbc.queryForObject("SELECT create_attempted_at IS NOT NULL FROM refund_order WHERE id=?", Boolean.class, refund.toString())).isTrue();
        assertThat(jdbc.queryForObject("SELECT reconcile_lease_until FROM refund_order WHERE id=?", java.sql.Timestamp.class, refund.toString())).isNotNull();
        assertThat(repository.claimRefundReconciliationDirect(refund, "reconciler", "token-reconcile")).isFalse();
        jdbc.update("UPDATE refund_order SET reconcile_lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", refund.toString());
        assertThat(repository.claimRefundReconciliationDirect(refund, "reconciler", "token-reconcile")).isTrue();
        assertThat(repository.claimInitialRefundAttempt(refund, "second-create", "token-create")).isFalse();
    }

    private UUID paidPayment(long amountFen) {
        UUID order = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        UUID seller = user();
        UUID buyer = user();
        UUID listing = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            listing.toString(), seller.toString(), "教材", "描述", "教材");
        jdbc.update("INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,1,?,?, 'AWAITING_HANDOFF',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            order.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "描述", amountFen, amountFen, amountFen);
        jdbc.update("INSERT INTO payment_order (id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,status,created_at,updated_at) VALUES (?,?,?,?,?,?,'SUCCEEDED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            payment.toString(), order.toString(), "simulated", "paid-" + payment, amountFen, amountFen);
        return payment;
    }

    private UUID pendingOrder() {
        UUID order = UUID.randomUUID();
        UUID seller = user();
        UUID buyer = user();
        UUID listing = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            listing.toString(), seller.toString(), "教材", "描述", "教材");
        jdbc.update("INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,0,'PENDING_PAYMENT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            order.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "描述");
        return order;
    }

    private HttpClient client() {
        return HttpClient.newHttpClient();
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_user (id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", id.toString(), id + "@stu.example.edu.cn", "hash");
        return id;
    }
}
