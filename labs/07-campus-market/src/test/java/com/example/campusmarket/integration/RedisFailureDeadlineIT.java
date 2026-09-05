package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.order.application.DeadlineScheduler;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 独立上下文中物理停止 Redis，证明截止任务仅依赖 MySQL 状态事实。 */
@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DeadlineScheduler scheduler;
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

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_user (id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            id.toString(), id + "@stu.example.edu.cn", "hash");
        return id;
    }
}
