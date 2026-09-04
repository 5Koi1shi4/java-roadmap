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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 支付成功推进订单、退款额度预占和重复回调幂等的真实 MySQL 流程测试。 */
@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class PaymentFlowIT extends SharedContainers {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentService payments;
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

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_user (id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", id.toString(), id + "@stu.example.edu.cn", "hash");
        return id;
    }
}
