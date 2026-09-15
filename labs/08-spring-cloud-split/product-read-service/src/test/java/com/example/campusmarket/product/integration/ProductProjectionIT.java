package com.example.campusmarket.product.integration;

import com.example.campusmarket.product.event.ProductEventConsumer;
import com.example.campusmarket.product.event.ProductSnapshotDecoder;
import com.example.campusmarket.product.infrastructure.JdbcProductInbox;
import com.example.campusmarket.product.infrastructure.JdbcProductIndexOutbox;
import com.example.campusmarket.product.infrastructure.JdbcProductProjection;
import com.example.campusmarket.testsupport.SplitDatabaseContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实读库证明 Inbox 幂等、版本条件写与索引待办一致。 */
@SpringBootTest(classes = ProductProjectionIT.TestApplication.class, properties = {
    "spring.main.web-application-type=none", "eureka.client.enabled=false",
    "spring.rabbitmq.listener.simple.auto-startup=false"
})
class ProductProjectionIT {
    @Autowired private ProductEventConsumer consumer;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void productDatabase(DynamicPropertyRegistry registry) {
        SplitDatabaseContainer.productProperties(registry);
    }

    @Test
    void duplicateEventIdWritesOneInboxProjectionAndIndexTask() {
        UUID listingId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        byte[] event = event(eventId, listingId, 1, "数据库教材");

        consumer.accept(event);
        consumer.accept(event);

        assertThat(count("product_inbox", "event_id", eventId)).isEqualTo(1);
        assertThat(count("product_projection", "listing_id", listingId)).isEqualTo(1);
        assertThat(count("product_index_outbox", "listing_id", listingId)).isEqualTo(1);
    }

    @Test
    void lateLowerVersionCannotRewindProjectionOrQueueOlderIndexTask() {
        UUID listingId = UUID.randomUUID();
        UUID newest = UUID.randomUUID();
        UUID stale = UUID.randomUUID();

        consumer.accept(event(newest, listingId, 3, "新版教材"));
        consumer.accept(event(stale, listingId, 2, "旧版教材"));

        assertThat(jdbc.queryForObject(
            "SELECT aggregate_version FROM product_projection WHERE listing_id=?", Long.class,
            listingId.toString())).isEqualTo(3);
        assertThat(jdbc.queryForObject(
            "SELECT title FROM product_projection WHERE listing_id=?", String.class,
            listingId.toString())).isEqualTo("新版教材");
        assertThat(count("product_inbox", "event_id", newest)).isEqualTo(1);
        assertThat(count("product_inbox", "event_id", stale)).isEqualTo(1);
        assertThat(count("product_index_outbox", "listing_id", listingId)).isEqualTo(1);
    }

    @Test
    void indexOutboxFailureRollsBackInboxAndProjectionTogether() {
        UUID listingId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO product_index_outbox(id,listing_id,aggregate_version,status)
            VALUES (?,?,1,'NEW')
            """, UUID.randomUUID().toString(), listingId.toString());

        assertThatThrownBy(() -> consumer.accept(event(eventId, listingId, 1, "并发教材")))
            .isInstanceOf(RuntimeException.class);

        assertThat(count("product_inbox", "event_id", eventId)).isZero();
        assertThat(count("product_projection", "listing_id", listingId)).isZero();
        assertThat(count("product_index_outbox", "listing_id", listingId)).isEqualTo(1);
    }

    private long count(String table, String column, UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?",
            Long.class, id.toString());
    }

    private static byte[] event(UUID eventId, UUID listingId, long version, String title) {
        return """
            {"eventId":"%s","listingId":"%s","aggregateVersion":%d,
             "eventType":"LISTING_PUBLISHED","occurredAt":"2026-09-15T00:00:00Z",
             "schemaVersion":2,
             "snapshot":{"title":"%s","description":"商品描述","category":"教材",
                         "unitPriceFen":3000,"availableQuantity":2,"status":"ON_SALE"}}
            """.formatted(eventId, listingId, version, title).getBytes(StandardCharsets.UTF_8);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({ProductEventConsumer.class, ProductSnapshotDecoder.class, JdbcProductInbox.class,
        JdbcProductProjection.class, JdbcProductIndexOutbox.class})
    static class TestApplication {
    }
}
