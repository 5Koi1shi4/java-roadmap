package com.example.campusmarket.product.integration;

import com.example.campusmarket.product.ProductReadApplication;
import com.example.campusmarket.testsupport.SplitDatabaseContainer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** 实际 RabbitMQ 投递与 ACK/死信，读服务自行声明协议队列。 */
@Testcontainers
@SpringBootTest(classes = ProductReadApplication.class, properties = {
    "spring.main.web-application-type=none", "eureka.client.enabled=false"
})
class ProductRabbitConsumerIT {
    private static final String PRODUCT_EXCHANGE = "campus.product.snapshot";
    private static final String MANUAL_QUEUE = "campus.market.manual.failure";

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management"));

    @Autowired private RabbitTemplate publisher;
    @Autowired private RabbitAdmin admin;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        SplitDatabaseContainer.productProperties(registry);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
    }

    @Test
    void duplicatedDurableMessageCommitsOneProjectionAndAck() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        byte[] payload = payload(eventId, listingId, 1, 2);

        publisher.convertAndSend(PRODUCT_EXCHANGE, "LISTING_PUBLISHED", payload);
        publisher.convertAndSend(PRODUCT_EXCHANGE, "LISTING_PUBLISHED", payload);

        await(() -> count("product_inbox", "event_id", eventId) == 1
            && count("product_index_outbox", "listing_id", listingId) == 1
            && queueCount("campus.product.read") == 0);
        assertThat(count("product_projection", "listing_id", listingId)).isEqualTo(1);
    }

    @Test
    void invalidSchemaVersionIsRejectedIntoInspectableDeadLetterQueue() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        byte[] invalid = new String(payload(eventId, listingId, 1, 2), StandardCharsets.UTF_8)
            .replace("\"schemaVersion\":2", "\"schemaVersion\":3")
            .getBytes(StandardCharsets.UTF_8);

        publisher.convertAndSend(PRODUCT_EXCHANGE, "LISTING_PUBLISHED", invalid);

        await(() -> queueCount(MANUAL_QUEUE) == 1 && queueCount("campus.product.read") == 0);
        assertThat(count("product_inbox", "event_id", eventId)).isZero();
        assertThat(count("product_projection", "listing_id", listingId)).isZero();
    }

    private long count(String table, String column, UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?",
            Long.class, id.toString());
    }

    private int queueCount(String queue) {
        Map<?, ?> properties = admin.getQueueProperties(queue);
        return properties == null ? -1 : (Integer) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline && !condition.getAsBoolean()) {
            Thread.sleep(100);
        }
        assertThat(condition.getAsBoolean()).as("Rabbit 投递未在 20 秒内收敛").isTrue();
    }

    private static byte[] payload(UUID eventId, UUID listingId, long version, int quantity) {
        return """
            {"eventId":"%s","listingId":"%s","aggregateVersion":%d,
             "eventType":"LISTING_PUBLISHED","occurredAt":"2026-09-15T00:00:00Z",
             "schemaVersion":2,
             "snapshot":{"title":"计算机教材","description":"完整教材","category":"教材",
                         "unitPriceFen":3000,"availableQuantity":%d,"status":"ON_SALE"}}
            """.formatted(eventId, listingId, version, quantity).getBytes(StandardCharsets.UTF_8);
    }
}
