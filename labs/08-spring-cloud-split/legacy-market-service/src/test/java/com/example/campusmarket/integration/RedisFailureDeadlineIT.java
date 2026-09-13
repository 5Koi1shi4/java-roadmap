package com.example.campusmarket.integration;

import com.example.campusmarket.legacy.LegacyMarketApplication;
import com.example.campusmarket.order.application.DeadlineScheduler;
import com.example.campusmarket.order.application.OrderLifecycleService;
import com.example.campusmarket.order.infrastructure.JdbcOrderLifecycleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 独立上下文中物理停止 Redis，证明截止任务仅依赖 MySQL 状态事实。 */
@SpringBootTest(classes = LegacyMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestPropertySource(properties = {
    "campus.market.payment.reconciliation.enabled=false",
    "campus.market.search.dispatcher.enabled=false",
    "campus.market.storage.cleanup.enabled=false",
    "campus.market.order.deadline.initial-delay-ms=86400000"
})
class RedisFailureDeadlineIT {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
        .withDatabaseName("campus_market_redis_failure")
        .withUsername("campus_market")
        .withPassword("campus_market_local");
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine"))
        .withExposedPorts(6379);

    static {
        Startables.deepStart(Stream.of(MYSQL, REDIS)).join();
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DeadlineScheduler scheduler;
    @Autowired private OrderLifecycleService lifecycle;
    @Autowired private JdbcOrderLifecycleRepository lifecycleRepository;
    @Autowired private StringRedisTemplate redis;

    @AfterAll
    static void stopDedicatedContainers() {
        if (REDIS.isRunning()) REDIS.stop();
        if (MYSQL.isRunning()) MYSQL.stop();
    }

    @Test
    void deadlineLifecycleRemainsAtomicWhileRedisIsPhysicallyStopped() {
        assertThat(redis.getConnectionFactory().getConnection().ping()).isEqualTo("PONG");
        UUID seller = user();
        UUID buyer = user();
        UUID listing = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            listing.toString(), seller.toString(), "教材", "描述", "教材");
        jdbc.update("INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,payment_deadline,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,0,'PENDING_PAYMENT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            order.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "描述");
        jdbc.update("INSERT INTO order_deadline_claim (id,order_id,deadline_type,status,due_at) VALUES (?,?, 'PAYMENT','NEW',CURRENT_TIMESTAMP(6))",
            UUID.randomUUID().toString(), order.toString());

        REDIS.stop();
        try {
            assertThat(scheduler.runOne(order)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("CANCELLED");
            assertThat(jdbc.queryForObject("SELECT available_quantity FROM listing WHERE id=?", Integer.class, listing.toString())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE business_key=?", Integer.class,
                "order:" + order + ":payment-timeout")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='ORDER_CANCELLED' AND aggregate_id=?", Integer.class,
                order.toString())).isEqualTo(1);
        } finally {
            if (!REDIS.isRunning()) REDIS.start();
        }
        assertThat(REDIS.isRunning()).isTrue();
    }

    @Test
    void handoffAndTimeoutRaceStillHasOneDatabaseWinnerWhileRedisIsStopped() throws Exception {
        UUID seller = user();
        UUID buyer = user();
        UUID listing = listing(seller, 0);
        UUID order = order(buyer, seller, listing, "AWAITING_HANDOFF");
        deadline(order, "HANDOFF");
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        REDIS.stop();
        try {
            var handoff = pool.submit(() -> { gate.await(); return lifecycle.handoff(order, seller, "redis outage handoff"); });
            var timeout = pool.submit(() -> { gate.await(); return scheduler.runOnce(10); });
            gate.countDown();
            boolean handoffWon = handoff.get(30, java.util.concurrent.TimeUnit.SECONDS);
            timeout.get(30, java.util.concurrent.TimeUnit.SECONDS);
            String status = jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString());
            assertThat(status).isIn("AWAITING_RECEIPT", "REFUNDING_CANCEL");
            assertThat(handoffWon).isEqualTo("AWAITING_RECEIPT".equals(status));
            assertThat(jdbc.queryForObject("SELECT available_quantity FROM listing WHERE id=?", Integer.class, listing.toString()))
                .isEqualTo("REFUNDING_CANCEL".equals(status) ? 1 : 0);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE aggregate_id=? AND event_type='ORDER_REFUNDING_CANCEL'", Integer.class, order.toString()))
                .isEqualTo("REFUNDING_CANCEL".equals(status) ? 1 : 0);
        } finally {
            pool.shutdownNow();
            if (!REDIS.isRunning()) REDIS.start();
        }
    }

    @Test
    void receiptT0AndOutboxAreWrittenOnceWhileRedisIsPhysicallyStopped() {
        UUID seller = user();
        UUID buyer = user();
        UUID listing = listing(seller, 0);
        UUID order = order(buyer, seller, listing, "AWAITING_RECEIPT");
        deadline(order, "RECEIPT");
        REDIS.stop();
        try {
            assertThat(scheduler.runOne(order)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("AFTERSALE_WINDOW");
            assertThat(jdbc.queryForObject("SELECT t0 IS NOT NULL FROM trade_order WHERE id=?", Boolean.class, order.toString())).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_transition WHERE order_id=? AND reason='AUTO_RECEIPT'", Integer.class, order.toString())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE aggregate_id=? AND event_type='ORDER_RECEIPT_CONFIRMED'", Integer.class, order.toString())).isEqualTo(1);
        } finally {
            if (!REDIS.isRunning()) REDIS.start();
        }
    }

    @Test
    void expiredOwnerCannotWriteReceiptAfterRedisStopsAndNewOwnerCanTakeOver() {
        UUID seller = user();
        UUID buyer = user();
        UUID listing = listing(seller, 0);
        UUID order = order(buyer, seller, listing, "AWAITING_RECEIPT");
        deadline(order, "RECEIPT");
        var oldClaim = lifecycleRepository.claimBatch("redis-old-owner", 1, java.time.Duration.ofSeconds(30)).get(0);
        jdbc.update("UPDATE order_deadline_claim SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE id=?", oldClaim.id().toString());
        REDIS.stop();
        try {
            assertThat(lifecycle.autoConfirmReceipt(order, oldClaim)).isFalse();
            assertThat(jdbc.queryForObject("SELECT t0 IS NULL FROM trade_order WHERE id=?", Boolean.class, order.toString())).isTrue();
            assertThat(scheduler.runOne(order)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("AFTERSALE_WINDOW");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE aggregate_id=? AND event_type='ORDER_RECEIPT_CONFIRMED'", Integer.class, order.toString())).isEqualTo(1);
        } finally {
            if (!REDIS.isRunning()) REDIS.start();
        }
    }

    private UUID listing(UUID seller, int available) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,100,? ,?,0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            id.toString(), seller.toString(), "教材", "描述", "教材", available, available == 0 ? "SOLD_OUT" : "ON_SALE");
        return id;
    }

    private UUID order(UUID buyer, UUID seller, UUID listing, String status) {
        UUID id = UUID.randomUUID();
        String handoff = "AWAITING_HANDOFF".equals(status) ? "CURRENT_TIMESTAMP(6)" : "NULL";
        String receipt = "AWAITING_RECEIPT".equals(status) ? "CURRENT_TIMESTAMP(6)" : "NULL";
        jdbc.update("INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,payment_deadline,handoff_deadline,receipt_deadline,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,100,?,0,CURRENT_TIMESTAMP(6)," + handoff + "," + receipt + ",CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            id.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "描述", status);
        return id;
    }

    private void deadline(UUID order, String type) {
        jdbc.update("INSERT INTO order_deadline_claim (id,order_id,deadline_type,status,due_at) VALUES (?,?,?,'NEW',CURRENT_TIMESTAMP(6))",
            UUID.randomUUID().toString(), order.toString(), type);
    }

    private UUID user() {
        return UUID.randomUUID();
    }
}
