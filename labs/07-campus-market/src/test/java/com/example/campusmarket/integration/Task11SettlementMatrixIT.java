package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.payment.application.SettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task11 结算资格矩阵：只使用真实 MySQL，避免启动无关基础设施。 */
@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "campus.market.search.dispatcher.enabled=false",
    "campus.market.dispute.deadline.enabled=false",
    "campus.market.dispute.return-reconciliation.enabled=false",
    "spring.rabbitmq.listener.simple.auto-startup=false",
    "spring.rabbitmq.listener.direct.auto-startup=false"
})
class Task11SettlementMatrixIT extends Task11MySqlContainers {
    @Autowired JdbcTemplate jdbc;
    @Autowired SettlementService settlements;

    @Test
    void warrantyNinetyDaysDoesNotDelaySettlementAfterSevenDays() {
        Fixture f = fixture(1, 100, 90, 8);
        assertThat(settlements.settle(f.order()).status()).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT warranty_days FROM trade_order WHERE id=?", Integer.class, f.order().toString())).isEqualTo(90);
    }

    @Test
    void trialWindowBeforeBoundaryIsBlockedUsingDatabaseFact() {
        Fixture f = fixture(1, 100, null, 6);
        assertThat(settlements.settle(f.order()).status()).isEqualTo("BLOCKED");
        assertThat(settlements.settle(f.order()).blockedReason()).isEqualTo("TRIAL_WINDOW");
    }

    @Test
    void trialWindowAtBoundaryAndAfterIsAllowedUsingDatabaseFact() {
        Fixture f = fixture(1, 100, null, 7);
        assertThat(settlements.settle(f.order()).status()).isEqualTo("SETTLED");
        assertThat(settlements.settle(f.order()).status()).isEqualTo("SETTLED");
    }

    @Test
    void processingRefundIndependentlyBlocksSettlement() {
        Fixture f = fixture(1, 100, null, 8);
        insertRefund(f, "PROCESSING");
        assertBlocked(f, "PENDING_REFUND");
    }

    @Test
    void unknownRefundIndependentlyBlocksSettlement() {
        Fixture f = fixture(1, 100, null, 8);
        insertRefund(f, "UNKNOWN");
        assertBlocked(f, "PENDING_REFUND");
    }

    @Test
    void reservedRefundIndependentlyBlocksSettlement() {
        Fixture f = fixture(1, 100, null, 8);
        jdbc.update("UPDATE payment_order SET reserved_refund_fen=1 WHERE id=?", f.payment().toString());
        assertBlocked(f, "REFUND_RESERVATION");
    }

    @Test
    void activeDisputeIndependentlyBlocksSettlement() {
        Fixture f = fixture(1, 100, null, 8);
        jdbc.update("INSERT INTO dispute_case (id,order_id,initiator_id,disputed_quantity,reason,status,seller_deadline,version,opened_at,created_at,updated_at) VALUES (?,?,?,1,'QUANTITY','OPEN',DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            UUID.randomUUID().toString(), f.order().toString(), f.buyer().toString());
        assertBlocked(f, "ACTIVE_DISPUTE");
    }

    @Test
    void repeatedSettlementKeepsSingleOutboxAndSettlement() {
        Fixture f = fixture(1, 100, null, 8);
        assertThat(settlements.settle(f.order()).status()).isEqualTo("SETTLED");
        assertThat(settlements.settle(f.order()).status()).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement WHERE order_id=?", Integer.class, f.order().toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='SETTLEMENT_CREATED' AND aggregate_id=?", Integer.class, f.order().toString())).isEqualTo(1);
    }

    private void assertBlocked(Fixture f, String reason) {
        var result = settlements.settle(f.order());
        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.blockedReason()).isEqualTo(reason);
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, f.order().toString())).isEqualTo("AFTERSALE_WINDOW");
    }

    private void insertRefund(Fixture f, String status) {
        jdbc.update("INSERT INTO refund_order (id,order_id,payment_order_id,provider,idempotency_key,source_type,source_id,paid_amount_fen,amount_fen,status,created_at,updated_at) VALUES (?,?,?,?,?,'DISPUTE',NULL,100,100,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            UUID.randomUUID().toString(), f.order().toString(), f.payment().toString(), "simulated", "matrix-" + UUID.randomUUID(), status);
    }

    private Fixture fixture(int quantity, long unitPrice, Integer warrantyDays, int daysAgo) {
        UUID buyer = user(), seller = user(), listing = UUID.randomUUID(), order = UUID.randomUUID(), payment = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?, ?,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            listing.toString(), seller.toString(), "教材", "描述", "教材", unitPrice);
        jdbc.update("INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,warranty_days,status,version,t0,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?, 'AFTERSALE_WINDOW',0,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL ? DAY),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            order.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "描述", unitPrice, quantity, unitPrice * quantity, warrantyDays, daysAgo);
        jdbc.update("INSERT INTO payment_order (id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,'SUCCEEDED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            payment.toString(), order.toString(), "simulated", "pay-" + payment, unitPrice * quantity, unitPrice * quantity, "sim-pay-" + payment);
        return new Fixture(buyer, seller, order, payment);
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_user (id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            id.toString(), id + "@stu.example.edu.cn", "hash");
        return id;
    }

    private record Fixture(UUID buyer, UUID seller, UUID order, UUID payment) {}
}
