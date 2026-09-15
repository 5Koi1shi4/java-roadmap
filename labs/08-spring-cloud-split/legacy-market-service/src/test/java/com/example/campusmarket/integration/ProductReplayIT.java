package com.example.campusmarket.integration;

import com.example.campusmarket.catalog.search.ProductReplayService;
import com.example.campusmarket.catalog.search.ProductSnapshotEvent;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 保留商品事件 replay 的真实数据库、RabbitMQ 与并发接管测试。 */
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
class ProductReplayIT extends Task12RabbitMySqlContainers {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired RabbitTemplate rabbit;
    @Autowired ProductReplayService replay;
    @Autowired ProductSnapshotPublisherDispatcher dispatcher;

    @BeforeEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM listing_media WHERE listing_id IN (SELECT id FROM listing WHERE title LIKE 'publisher-%' OR title LIKE 'replay-%')");
        jdbc.update("DELETE FROM search_outbox WHERE listing_id IN (SELECT id FROM listing WHERE title LIKE 'publisher-%' OR title LIKE 'replay-%')");
        jdbc.update("DELETE FROM listing WHERE title LIKE 'publisher-%' OR title LIKE 'replay-%'");
        jdbc.update("UPDATE product_replay_claim SET high_watermark=0,next_sequence_no=1,owner_id=NULL,claim_token=NULL,lease_until=NULL,status='IDLE'");
    }

    @Test
    void replayFixesHighWaterMarkAndConcurrentNewEventUsesNormalPublisher() throws Exception {
        ProductSnapshotEvent first = insertEvent("PUBLISHED");
        ProductSnapshotEvent second = insertEvent("PUBLISHED");

        // A bounded first invocation fixes the replay high-water mark at the
        // current max sequence while processing only the first old event.
        assertThat(replay.replayOnce(1)).isEqualTo(1);
        assertThat(receiveEvent()).isEqualTo(first.eventId());

        ProductSnapshotEvent concurrent = insertEvent("NEW");

        assertThat(replay.replayOnce(10)).isEqualTo(1);
        assertThat(receiveEvent()).isEqualTo(second.eventId());
        assertThat(rabbit.receive(RabbitTopology.PRODUCT_QUEUE, 100)).isNull();

        assertThat(dispatcher.dispatchOnce(1)).isEqualTo(1);
        assertThat(receiveEvent()).isEqualTo(concurrent.eventId());
    }

    @Test
    void expiredReplayLeaseCanBeTakenOverAndOldPublishedEventRemainsReplayable() throws Exception {
        ProductSnapshotEvent first = insertEvent("PUBLISHED");
        ProductSnapshotEvent second = insertEvent("PUBLISHED");

        assertThat(replay.replayOnce(1)).isEqualTo(1);
        assertThat(receiveEvent()).isEqualTo(first.eventId());
        jdbc.update("UPDATE product_replay_claim SET owner_id='crashed-replay-owner',claim_token='crashed-replay-token',"
            + "lease_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 MICROSECOND");

        assertThat(replay.replayOnce(1)).isEqualTo(1);
        assertThat(receiveEvent()).isEqualTo(second.eventId());
        assertThat(jdbc.queryForObject("SELECT next_sequence_no > high_watermark FROM product_replay_claim",
            Boolean.class)).isTrue();
    }

    private UUID receiveEvent() {
        Message message = rabbit.receive(RabbitTopology.PRODUCT_QUEUE, 5_000);
        assertThat(message).isNotNull();
        try {
            return UUID.fromString(mapper.readTree(message.getBody()).path("eventId").asText());
        } catch (Exception e) {
            throw new AssertionError("商品事件 JSON 无效", e);
        }
    }

    private ProductSnapshotEvent insertEvent(String status) throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        ProductSnapshotEvent event = new ProductSnapshotEvent(eventId, listingId, 1, "LISTING_PUBLISHED",
            Instant.parse("2026-09-15T01:02:03.123456Z"), ProductSnapshotEvent.CURRENT_SCHEMA_VERSION,
            new ProductSnapshotEvent.ProductSnapshot("商品标题", "完整描述", "教材", 12900, 3, "ON_SALE"));
        jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,"
                + "quarantined_quantity,status,version,created_at,updated_at) VALUES (?,?,?,'描述','教材',12900,3,0,'ON_SALE',1,"
                + "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listingId.toString(), UUID.randomUUID().toString(),
            "replay-fixture-" + listingId);
        jdbc.update("INSERT INTO search_outbox(id,listing_id,aggregate_version,event_type,payload,schema_version,status,"
                + "attempt_count,available_at,created_at) VALUES (?,?,?,?,CAST(? AS JSON),?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            event.eventId().toString(), listingId.toString(), event.aggregateVersion(), event.eventType(),
            mapper.writeValueAsString(event), event.schemaVersion(), status, 0);
        return event;
    }
}
