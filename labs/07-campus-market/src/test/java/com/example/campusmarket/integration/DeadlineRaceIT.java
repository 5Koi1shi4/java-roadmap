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

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** MySQL 截止时间条件与库存/退款事件原子性的真实集成测试。 */
@SpringBootTest(classes = CampusMarketApplication.class)
@ActiveProfiles("local")
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
    void trialCloseAndUserTransitionRaceCannotReopenOrDoublePublish() throws Exception {
        UUID seller = user(); UUID buyer = user(); UUID listing = listing(seller, 0);
        UUID order = order(buyer, seller, listing, "AFTERSALE_WINDOW", "CURRENT_TIMESTAMP(6)", "DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND)");
        deadline(order, "TRIAL", "CURRENT_TIMESTAMP(6)");
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var close = pool.submit(() -> { gate.await(); return scheduler.runOnce(10); });
            var userTransition = pool.submit(() -> { gate.await(); return jdbc.update("UPDATE trade_order SET status='DISPUTED',version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='AFTERSALE_WINDOW' AND trial_deadline<=CURRENT_TIMESTAMP(6)", order.toString()); });
            gate.countDown();
            close.get(30, TimeUnit.SECONDS); userTransition.get(30, TimeUnit.SECONDS);
            String status = jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString());
            assertThat(status).isIn("SETTLED", "DISPUTED");
            assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isNotEqualTo("AFTERSALE_WINDOW");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='ORDER_SETTLED' AND aggregate_id=?", Integer.class, order.toString())).isEqualTo("SETTLED".equals(status) ? 1 : 0);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void schedulerHasNoRedisCorrectnessDependency() {
        assertThat(java.util.Arrays.stream(DeadlineScheduler.class.getDeclaredFields())
            .noneMatch(field -> field.getType().getName().toLowerCase().contains("redis"))).isTrue();
    }

    private UUID listing(UUID seller, int available) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,100,? ,?,0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            id.toString(), seller.toString(), "教材", "描述", "教材", available, available == 0 ? "SOLD_OUT" : "ON_SALE");
        return id;
    }

    private UUID order(UUID buyer, UUID seller, UUID listing, String status, String handoffDeadline, String trialDeadline) {
        UUID id = UUID.randomUUID();
        String sql = "INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,payment_deadline,handoff_deadline,receipt_deadline,trial_deadline,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,100,?,0,CURRENT_TIMESTAMP(6)," + handoffDeadline + "," + ("AWAITING_RECEIPT".equals(status) ? "CURRENT_TIMESTAMP(6)" : "NULL") + "," + trialDeadline + ",CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))";
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
