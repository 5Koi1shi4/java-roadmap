package com.example.campusmarket.integration;

import com.example.campusmarket.catalog.application.ListingService;
import com.example.campusmarket.catalog.search.ProductSnapshotEvent;
import com.example.campusmarket.catalog.search.ProductSnapshotOutboxClaimer;
import com.example.campusmarket.catalog.search.ProductSnapshotPublisherDispatcher;
import com.example.campusmarket.legacy.LegacyMarketApplication;
import com.example.campusmarket.messaging.RabbitTopology;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 商品快照生产者的真实 MySQL/RabbitMQ 边界测试。 */
@SpringBootTest(classes = LegacyMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@TestPropertySource(properties = {
    "spring.rabbitmq.listener.simple.auto-startup=false",
    "spring.rabbitmq.listener.direct.auto-startup=false",
    "management.endpoint.health.group.readiness.include=readinessState",
    "campus.market.search.dispatcher.enabled=false",
    "campus.market.product.publisher.enabled=false",
    "campus.market.order.deadline.enabled=false",
    "campus.market.payment.reconciliation.enabled=false",
    "campus.market.dispute.deadline.enabled=false",
    "campus.market.dispute.return-reconciliation.enabled=false",
    "campus.market.warranty.deadline.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductPublisherIT extends Task12RabbitMySqlContainers {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired RabbitTemplate rabbit;
    @Autowired ProductSnapshotPublisherDispatcher dispatcher;
    @Autowired ProductSnapshotOutboxClaimer claimer;
    @Autowired ListingService listings;

    @BeforeEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM listing_media WHERE listing_id IN (SELECT id FROM listing WHERE title LIKE 'publisher-%')");
        jdbc.update("DELETE FROM search_outbox WHERE listing_id IN (SELECT id FROM listing WHERE title LIKE 'publisher-%')");
        jdbc.update("DELETE FROM listing WHERE title LIKE 'publisher-%'");
    }

    @Test
    void unroutableConfirmDoesNotMarkSnapshotPublished() throws Exception {
        ProductSnapshotEvent event = insertEvent("NEW", 0);
        rabbit.execute(channel -> {
            channel.queueUnbind(RabbitTopology.PRODUCT_QUEUE, RabbitTopology.PRODUCT_EXCHANGE, event.eventType());
            return null;
        });
        try {
            assertThat(dispatcher.dispatchOnce(1)).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM search_outbox WHERE id=?", String.class,
                event.eventId().toString())).isEqualTo("NEW");
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM search_outbox WHERE id=?", Integer.class,
                event.eventId().toString())).isEqualTo(1);
        } finally {
            rabbit.execute(channel -> {
                channel.queueBind(RabbitTopology.PRODUCT_QUEUE, RabbitTopology.PRODUCT_EXCHANGE, event.eventType());
                return null;
            });
        }
    }

    @Test
    void realPublishFailuresStopAfterThreeAttemptsAndRetainReplayRow() throws Exception {
        ProductSnapshotEvent event = insertEvent("NEW", 0);
        rabbit.execute(channel -> {
            channel.queueUnbind(RabbitTopology.PRODUCT_QUEUE, RabbitTopology.PRODUCT_EXCHANGE, event.eventType());
            return null;
        });
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                assertThat(dispatcher.dispatchOnce(1)).isZero();
                jdbc.update("UPDATE search_outbox SET available_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                    event.eventId().toString());
            }
            assertThat(jdbc.queryForObject("SELECT status FROM search_outbox WHERE id=?", String.class,
                event.eventId().toString())).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("SELECT failure_class FROM search_outbox WHERE id=?", String.class,
                event.eventId().toString())).isEqualTo("EXHAUSTED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM search_outbox WHERE id=?", Integer.class,
                event.eventId().toString())).isEqualTo(1);
        } finally {
            rabbit.execute(channel -> {
                channel.queueBind(RabbitTopology.PRODUCT_QUEUE, RabbitTopology.PRODUCT_EXCHANGE, event.eventType());
                return null;
            });
        }
    }

    @Test
    void persistentConfirmMarksPublishedAndLeavesDurableProductMessage() throws Exception {
        ProductSnapshotEvent event = insertEvent("NEW", 0);

        assertThat(dispatcher.dispatchOnce(1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM search_outbox WHERE id=?", String.class,
            event.eventId().toString())).isEqualTo("PUBLISHED");

        Message message = rabbit.receive(RabbitTopology.PRODUCT_QUEUE, 5_000);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.eventId().toString());
        assertThat(message.getMessageProperties().getReceivedDeliveryMode())
            .isEqualTo(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
        assertThat(mapper.readTree(message.getBody()).path("eventId").asText())
            .isEqualTo(event.eventId().toString());
    }

    @Test
    void failedBrokerDeliveryRetainsFactAndNewOutboxForLaterRecovery() {
        UUID seller = UUID.randomUUID();
        UUID listing = UUID.randomUUID();
        jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,"
                + "quarantined_quantity,status,version,created_at,updated_at) VALUES (?,?,?,'描述','教材',100,1,0,'DRAFT',0,"
                + "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString(),
            "publisher-outage-" + listing);
        jdbc.update("INSERT INTO listing_media(id,listing_id,object_key,media_type,size_bytes,sort_order,created_at) "
                + "VALUES (?,?,?,'image/jpeg',1,0,CURRENT_TIMESTAMP(6))", UUID.randomUUID().toString(),
            listing.toString(), "publisher-private-object-" + listing);

        // The production publisher is asynchronous. A broker outage therefore
        // cannot roll back the already committed fact transaction.
        listings.publish(seller, listing);

        assertThat(jdbc.queryForObject("SELECT status FROM listing WHERE id=?", String.class, listing.toString()))
            .isEqualTo("ON_SALE");
        assertThat(jdbc.queryForObject("SELECT status FROM search_outbox WHERE listing_id=? ORDER BY sequence_no DESC LIMIT 1",
            String.class, listing.toString())).isEqualTo("NEW");
    }

    @Test
    void oldOwnerCannotCompleteAfterLeaseTakeover() throws Exception {
        ProductSnapshotEvent event = insertEvent("NEW", 0);
        ProductSnapshotOutboxClaimer.Claim old = claimer.claim("product-old-owner", 1, Duration.ofSeconds(1))
            .get(0);
        jdbc.update("UPDATE search_outbox SET lease_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 MICROSECOND WHERE id=?",
            event.eventId().toString());
        ProductSnapshotOutboxClaimer.Claim current = claimer.claim("product-new-owner", 1, Duration.ofMinutes(1))
            .get(0);

        assertThat(claimer.complete(old)).isZero();
        assertThat(claimer.complete(current)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM search_outbox WHERE id=?", String.class,
            event.eventId().toString())).isEqualTo("PUBLISHED");
    }

    private ProductSnapshotEvent insertEvent(String status, int attempts) throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        ProductSnapshotEvent event = new ProductSnapshotEvent(eventId, listingId, 1, "LISTING_PUBLISHED",
            Instant.parse("2026-09-15T01:02:03.123456Z"), ProductSnapshotEvent.CURRENT_SCHEMA_VERSION,
            new ProductSnapshotEvent.ProductSnapshot("商品标题", "完整描述", "教材", 12900, 3, "ON_SALE"));
        jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,"
                + "quarantined_quantity,status,version,created_at,updated_at) VALUES (?,?,?,'描述','教材',12900,3,0,'ON_SALE',1,"
                + "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listingId.toString(), UUID.randomUUID().toString(),
            "publisher-fixture-" + listingId);
        jdbc.update("INSERT INTO search_outbox(id,listing_id,aggregate_version,event_type,payload,schema_version,status,"
                + "attempt_count,available_at,created_at) VALUES (?,?,?,?,CAST(? AS JSON),?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            event.eventId().toString(), listingId.toString(), event.aggregateVersion(), event.eventType(),
            mapper.writeValueAsString(event), event.schemaVersion(), status, attempts);
        return event;
    }
}
