package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.order.application.DeadlineScheduler;
import com.example.campusmarket.order.application.OrderLifecycleService;
import com.example.campusmarket.order.infrastructure.JdbcOrderLifecycleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** MySQL 截止时间条件与库存/退款事件原子性的真实集成测试。 */
@SpringBootTest(classes = CampusMarketApplication.class)
@ActiveProfiles("local")
@TestPropertySource(properties = {"campus.market.order.deadline.initial-delay-ms=86400000"})
class DeadlineRaceIT extends SharedContainers {
    @Autowired JdbcTemplate jdbc;
    @Autowired DeadlineScheduler scheduler;
    @Autowired OrderLifecycleService lifecycle;
    @Autowired JdbcOrderLifecycleRepository lifecycleRepository;

    @Test
    void handoffDeadlineWinnerRestoresInventoryAndWritesOneRefundEvent() {
        UUID seller = user();
        UUID buyer = user();
        UUID listing = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            listing.toString(), seller.toString(), "教材", "描述", "教材");
        jdbc.update("INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,handoff_deadline,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,100,'AWAITING_HANDOFF',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            order.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "描述");
        jdbc.update("INSERT INTO order_deadline_claim (id,order_id,deadline_type,status,due_at) VALUES (?,?, 'HANDOFF','NEW',CURRENT_TIMESTAMP(6))",
            UUID.randomUUID().toString(), order.toString());

        assertThat(scheduler.runOnce(10)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("REFUNDING_CANCEL");
        assertThat(jdbc.queryForObject("SELECT available_quantity FROM listing WHERE id=?", Integer.class, listing.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE business_key=?", Integer.class, "order:" + order + ":handoff-timeout")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='ORDER_REFUNDING_CANCEL' AND aggregate_id=?", Integer.class, order.toString())).isEqualTo(1);
    }

    @Test
    void sellerHandoffAtDeadlineIsRejectedByDatabaseTime() {
        UUID seller = user();
        UUID buyer = user();
        UUID listing = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            listing.toString(), seller.toString(), "教材", "描述", "教材");
        jdbc.update("INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,handoff_deadline,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,100,'AWAITING_HANDOFF',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            order.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "描述");
        assertThat(lifecycle.handoff(order, seller, "当面交付")).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("AWAITING_HANDOFF");
    }

    @Test
    void paymentTimeoutRestoresInventoryOnceWithoutRefundOutbox() {
        UUID seller = user(); UUID buyer = user(); UUID listing = listing(seller, 0);
        UUID order = order(buyer, seller, listing, "PENDING_PAYMENT", "CURRENT_TIMESTAMP(6)", "CURRENT_TIMESTAMP(6)");
        deadline(order, "PAYMENT", "CURRENT_TIMESTAMP(6)");

        assertThat(scheduler.runOnce(10)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT available_quantity FROM listing WHERE id=?", Integer.class, listing.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE business_key=?", Integer.class,
            "order:" + order + ":payment-timeout")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='ORDER_CANCELLED' AND aggregate_id=?", Integer.class, order.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='ORDER_REFUNDING_CANCEL' AND aggregate_id=?", Integer.class, order.toString())).isZero();
    }

    @Test
    void concurrentHandoffAndTimeoutHaveOneDatabaseWinnerAndOneInventoryOutcome() throws Exception {
        UUID seller = user(); UUID buyer = user(); UUID listing = listing(seller, 0);
        UUID order = order(buyer, seller, listing, "AWAITING_HANDOFF", "CURRENT_TIMESTAMP(6)", "CURRENT_TIMESTAMP(6)");
        deadline(order, "HANDOFF", "CURRENT_TIMESTAMP(6)");
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var handoff = pool.submit(() -> { gate.await(); return lifecycle.handoff(order, seller, "并发交付"); });
            var timeout = pool.submit(() -> { gate.await(); return scheduler.runOnce(10); });
            gate.countDown();
            boolean handoffWon = handoff.get(30, TimeUnit.SECONDS);
            timeout.get(30, TimeUnit.SECONDS);
            String status = jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString());
            int restored = jdbc.queryForObject("SELECT available_quantity FROM listing WHERE id=?", Integer.class, listing.toString());
            int refunds = jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='ORDER_REFUNDING_CANCEL' AND aggregate_id=?", Integer.class, order.toString());
            assertThat(status).isIn("AWAITING_RECEIPT", "REFUNDING_CANCEL");
            assertThat(handoffWon).isEqualTo("AWAITING_RECEIPT".equals(status));
            assertThat(restored).isEqualTo("REFUNDING_CANCEL".equals(status) ? 1 : 0);
            assertThat(refunds).isEqualTo("REFUNDING_CANCEL".equals(status) ? 1 : 0);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void concurrentHandoffBeforeDeadlineWinsAgainstDueClaim() throws Exception {
        UUID seller = user(); UUID buyer = user(); UUID listing = listing(seller, 0);
        UUID order = order(buyer, seller, listing, "AWAITING_HANDOFF", "DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 HOUR)", "CURRENT_TIMESTAMP(6)");
        deadline(order, "HANDOFF", "CURRENT_TIMESTAMP(6)");
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var handoff = pool.submit(() -> { gate.await(); return lifecycle.handoff(order, seller, "截止前交付"); });
            var dueClaim = pool.submit(() -> { gate.await(); return scheduler.runOnce(10); });
            gate.countDown();
            assertThat(handoff.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(dueClaim.get(30, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("AWAITING_RECEIPT");
            assertThat(jdbc.queryForObject("SELECT available_quantity FROM listing WHERE id=?", Integer.class, listing.toString())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='ORDER_REFUNDING_CANCEL' AND aggregate_id=?", Integer.class, order.toString())).isZero();
        } finally { pool.shutdownNow(); }
    }

    @Test
    void receiptTimeoutWritesT0ExactlyOnceAndDoesNotRepeatOutbox() {
        UUID seller = user(); UUID buyer = user(); UUID listing = listing(seller, 0);
        UUID order = order(buyer, seller, listing, "AWAITING_RECEIPT", "CURRENT_TIMESTAMP(6)", "CURRENT_TIMESTAMP(6)");
        deadline(order, "RECEIPT", "CURRENT_TIMESTAMP(6)");

        assertThat(scheduler.runOnce(10)).isEqualTo(1);
        var firstT0 = jdbc.queryForObject("SELECT t0 FROM trade_order WHERE id=?", java.sql.Timestamp.class, order.toString());
        assertThat(firstT0).isNotNull();
        assertThat(scheduler.runOnce(10)).isZero();
        assertThat(jdbc.queryForObject("SELECT t0 FROM trade_order WHERE id=?", java.sql.Timestamp.class, order.toString())).isEqualTo(firstT0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_transition WHERE order_id=? AND reason='AUTO_RECEIPT'", Integer.class, order.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='ORDER_RECEIPT_CONFIRMED' AND aggregate_id=?", Integer.class, order.toString())).isEqualTo(1);
    }

    @Test
    void expiredOwnerCannotConfirmReceiptAfterLeaseFencingAndNewOwnerCan() {
        UUID seller = user(); UUID buyer = user(); UUID listing = listing(seller, 0);
        UUID order = order(buyer, seller, listing, "AWAITING_RECEIPT", "CURRENT_TIMESTAMP(6)", "CURRENT_TIMESTAMP(6)");
        deadline(order, "RECEIPT", "CURRENT_TIMESTAMP(6)");
        var oldClaim = lifecycleRepository.claimBatch("old-owner", 1, java.time.Duration.ofSeconds(30)).get(0);
        jdbc.update("UPDATE order_deadline_claim SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE id=?", oldClaim.id().toString());

        assertThat(lifecycle.autoConfirmReceipt(order, oldClaim)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("AWAITING_RECEIPT");
        assertThat(scheduler.runOnce(10)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("AFTERSALE_WINDOW");
    }

    @Test
    void handoffBeforeDeadlineWritesHandoffRecordReceiptDeadlineTransitionAndOutbox() {
        UUID seller = user(); UUID buyer = user(); UUID listing = listing(seller, 0);
        UUID order = order(buyer, seller, listing, "AWAITING_HANDOFF", "DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 48 HOUR)", "CURRENT_TIMESTAMP(6)");

        assertThat(lifecycle.handoff(order, seller, "按时交付")).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("AWAITING_RECEIPT");
        assertThat(jdbc.queryForObject("SELECT receipt_deadline > CURRENT_TIMESTAMP(6) FROM trade_order WHERE id=?", Boolean.class, order.toString())).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM handoff_record WHERE order_id=?", Integer.class, order.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_transition WHERE order_id=? AND reason='SELLER_HANDOFF'", Integer.class, order.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='ORDER_HANDOFF_CONFIRMED' AND aggregate_id=?", Integer.class, order.toString())).isEqualTo(1);
    }

    @Test
    void receiptAtFortyEightHourBoundaryIsRejectedButBeforeBoundarySucceeds() {
        UUID seller = user(); UUID buyer = user(); UUID listing = listing(seller, 0);
        UUID before = order(buyer, seller, listing, "AWAITING_RECEIPT", "CURRENT_TIMESTAMP(6)", "CURRENT_TIMESTAMP(6)");
        jdbc.update("UPDATE trade_order SET receipt_deadline=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 HOUR) WHERE id=?", before.toString());
        assertThat(lifecycle.confirmReceipt(before, buyer)).isTrue();

        UUID at = order(buyer, seller, listing, "AWAITING_RECEIPT", "CURRENT_TIMESTAMP(6)", "CURRENT_TIMESTAMP(6)");
        jdbc.update("UPDATE trade_order SET receipt_deadline=CURRENT_TIMESTAMP(6) WHERE id=?", at.toString());
        assertThat(lifecycle.confirmReceipt(at, buyer)).isFalse();
        assertThat(jdbc.queryForObject("SELECT t0 IS NULL FROM trade_order WHERE id=?", Boolean.class, at.toString())).isTrue();
    }

    @Test
    void receiptSwitchesToSeventyTwoHourAcceptanceAndSevenDayTrialUsingDatabaseTime() {
        UUID seller = user(); UUID buyer = user(); UUID listing = listing(seller, 0);
        UUID order = order(buyer, seller, listing, "AWAITING_RECEIPT", "CURRENT_TIMESTAMP(6)", "CURRENT_TIMESTAMP(6)");
        jdbc.update("UPDATE trade_order SET receipt_deadline=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 HOUR) WHERE id=?", order.toString());
        assertThat(lifecycle.confirmReceipt(order, buyer)).isTrue();
        assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,t0,acceptance_deadline) FROM trade_order WHERE id=?", Long.class, order.toString())).isEqualTo(72L * 3600L);
        assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,t0,trial_deadline) FROM trade_order WHERE id=?", Long.class, order.toString())).isEqualTo(7L * 24L * 3600L);
    }

    @Test
    void trialDeadlineLeavesOrderAwaitingSettlementAndEmitsOneTask11Signal() {
        UUID seller = user(); UUID buyer = user(); UUID listing = listing(seller, 0);
        UUID order = order(buyer, seller, listing, "AFTERSALE_WINDOW", "CURRENT_TIMESTAMP(6)", "DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND)");
        jdbc.update("UPDATE trade_order SET t0=DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 8 DAY),acceptance_deadline=DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 5 DAY) WHERE id=?", order.toString());
        deadline(order, "TRIAL", "CURRENT_TIMESTAMP(6)");

        assertThat(scheduler.runOnce(10)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("AFTERSALE_WINDOW");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='ORDER_TRIAL_ELAPSED' AND aggregate_id=?", Integer.class, order.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='ORDER_SETTLED' AND aggregate_id=?", Integer.class, order.toString())).isZero();
        assertThat(scheduler.runOnce(10)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='ORDER_TRIAL_ELAPSED' AND aggregate_id=?", Integer.class, order.toString())).isEqualTo(1);
    }

    @Test
    void thirdDeadlineFailureAtomicallyFailsClaimAndCreatesManualFailureFact() {
        UUID seller = user(); UUID buyer = user(); UUID listing = listing(seller, 0);
        UUID order = order(buyer, seller, listing, "PENDING_PAYMENT", "CURRENT_TIMESTAMP(6)", "CURRENT_TIMESTAMP(6)");
        deadline(order, "PAYMENT", "CURRENT_TIMESTAMP(6)");
        JdbcOrderLifecycleRepository.DeadlineClaim claim = lifecycleRepository.claimBatch("failure-owner", 1, java.time.Duration.ofSeconds(30)).get(0);
        assertThat(lifecycleRepository.retryOrFailClaim(claim, "first failure")).isEqualTo(1);
        jdbc.update("UPDATE order_deadline_claim SET due_at=CURRENT_TIMESTAMP(6) WHERE id=?", claim.id().toString());
        claim = lifecycleRepository.claimBatch("failure-owner", 1, java.time.Duration.ofSeconds(30)).get(0);
        assertThat(lifecycleRepository.retryOrFailClaim(claim, "second failure")).isEqualTo(1);
        jdbc.update("UPDATE order_deadline_claim SET due_at=CURRENT_TIMESTAMP(6) WHERE id=?", claim.id().toString());
        claim = lifecycleRepository.claimBatch("failure-owner", 1, java.time.Duration.ofSeconds(30)).get(0);
        assertThat(lifecycleRepository.retryOrFailClaim(claim, "third failure")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM order_deadline_claim WHERE id=?", String.class, claim.id().toString())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM manual_failure WHERE source_type='ORDER_DEADLINE' AND source_id=?", Integer.class, claim.id().toString())).isEqualTo(1);
        assertThat(lifecycleRepository.claimBatch("another-owner", 10, java.time.Duration.ofSeconds(30))).isEmpty();
    }

    private UUID listing(UUID seller, int available) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,100,? ,?,0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            id.toString(), seller.toString(), "教材", "描述", "教材", available, available == 0 ? "SOLD_OUT" : "ON_SALE");
        return id;
    }

    private UUID order(UUID buyer, UUID seller, UUID listing, String status, String handoffDeadline, String trialDeadline) {
        UUID id = UUID.randomUUID();
        String trial = "AFTERSALE_WINDOW".equals(status) ? trialDeadline : "NULL";
        String sql = "INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,payment_deadline,handoff_deadline,receipt_deadline,acceptance_deadline,trial_deadline,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,100,?,0,CURRENT_TIMESTAMP(6)," + handoffDeadline + "," + ("AWAITING_RECEIPT".equals(status) ? "CURRENT_TIMESTAMP(6)" : "NULL") + ",NULL," + trial + ",CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))";
        jdbc.update(sql, id.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "描述", status);
        return id;
    }

    private void deadline(UUID order, String type, String dueAt) {
        jdbc.update("INSERT INTO order_deadline_claim (id,order_id,deadline_type,status,due_at) VALUES (?,?,?,'NEW'," + dueAt + ")",
            UUID.randomUUID().toString(), order.toString(), type);
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_user (id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            id.toString(), id + "@stu.example.edu.cn", "hash");
        return id;
    }
}
