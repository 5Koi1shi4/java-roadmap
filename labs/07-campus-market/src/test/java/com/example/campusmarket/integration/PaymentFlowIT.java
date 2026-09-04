package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.payment.application.PaymentGateway;
import com.example.campusmarket.payment.application.PaymentService;
import com.example.campusmarket.payment.application.RefundService;
import com.example.campusmarket.payment.infrastructure.JdbcPaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 支付成功推进订单、退款额度预占和重复回调幂等的真实 MySQL 流程测试。 */
@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class PaymentFlowIT extends SharedContainers {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentService payments;
    @Autowired private RefundService refunds;
    @Autowired private JdbcPaymentRepository repository;

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
        PaymentGateway.VerifiedCallback wrongReference = new PaymentGateway.VerifiedCallback("simulated", "event-unknown-reference-" + first,
            PaymentGateway.VerifiedCallback.CallbackType.REFUND, "provider-not-linked", 40, "SUCCEEDED", Instant.now(), UUID.randomUUID().toString());
        refunds.handleCallback(wrongReference, "{\"unknownReference\":true}".getBytes(StandardCharsets.UTF_8));
        assertThat(jdbc.queryForObject("SELECT reserved_refund_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(80L);
        jdbc.update("UPDATE refund_order SET provider_reference='provider-second' WHERE id=?", second.toString());
        PaymentGateway.VerifiedCallback secondSuccess = new PaymentGateway.VerifiedCallback("simulated", "event-second-" + second,
            PaymentGateway.VerifiedCallback.CallbackType.REFUND, "provider-second", 40, "SUCCEEDED", Instant.now(), UUID.randomUUID().toString());
        refunds.handleCallback(secondSuccess, "{\"second\":true}".getBytes(StandardCharsets.UTF_8));
        assertThat(jdbc.queryForObject("SELECT reserved_refund_fen FROM payment_order WHERE id=?", Long.class, payment.toString())).isEqualTo(40L);
        assertThat(jdbc.queryForObject("SELECT status FROM refund_order WHERE id=?", String.class, first.toString())).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForObject("SELECT status FROM refund_order WHERE id=?", String.class, second.toString())).isEqualTo("SUCCEEDED");
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

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_user (id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", id.toString(), id + "@stu.example.edu.cn", "hash");
        return id;
    }
}
