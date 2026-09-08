package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.catalog.search.ProductSearchPort;
import com.example.campusmarket.catalog.search.SearchOutboxDispatcher;
import com.example.campusmarket.messaging.OutboxDispatcher;
import com.example.campusmarket.storage.StorageCleanupScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.ToxiproxyContainer;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 三轮可重复的外部故障演练：每轮都显式断开、恢复并运行收敛器。 */
@SpringBootTest(classes = CampusMarketApplication.class)
@ActiveProfiles("local")
@TestPropertySource(properties = {
    "campus.market.order.deadline.enabled=false",
    "campus.market.search.dispatcher.enabled=false",
    "campus.market.payment.reconciliation.enabled=false",
    "campus.market.dispute.deadline.enabled=false",
    "campus.market.dispute.return-reconciliation.enabled=false",
    "campus.market.warranty.deadline.enabled=false",
    "spring.task.scheduling.enabled=false",
    "spring.rabbitmq.listener.simple.auto-startup=false",
    "spring.rabbitmq.listener.direct.auto-startup=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RecoveryDrillIT extends SharedContainers {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OutboxDispatcher outbox;
    @Autowired private SearchOutboxDispatcher searchOutbox;
    @Autowired private ProductSearchPort search;
    @Autowired private StorageCleanupScheduler cleanup;

    @Test
    void round1RabbitDisconnectLeavesOutboxPendingThenPublishesAfterRecovery() {
        UUID event = UUID.randomUUID();
        insertOutbox(event, "ORDER_CREATED", UUID.randomUUID());
        RABBIT_PROXY.setConnectionCut(true);
        try {
            outbox.dispatchOnce(10, Duration.ofSeconds(2));
            assertThat(status("integration_outbox", event)).isIn("NEW", "PUBLISHING");
        } finally {
            RABBIT_PROXY.setConnectionCut(false);
        }
        jdbc.update("UPDATE integration_outbox SET available_at=CURRENT_TIMESTAMP(6),lease_until=NULL WHERE event_id=?", event.toString());
        outbox.dispatchOnce(10, Duration.ofSeconds(30));
        assertThat(status("integration_outbox", event)).isEqualTo("PUBLISHED");
        assertInvariants();
    }

    @Test
    void round2ElasticsearchDisconnectLeavesSearchOutboxPendingThenCatchesUp() {
        UUID seller = user();
        UUID listing = UUID.randomUUID();
        jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,'desc','教材',100,2,'ON_SALE',1,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString(), "故障演练教材");
        UUID event = UUID.randomUUID();
        jdbc.update("INSERT INTO search_outbox(id,listing_id,aggregate_version,event_type,payload,status,attempt_count,available_at,created_at) VALUES (?,?,1,'LISTING_PUBLISHED',CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", event.toString(), listing.toString(), "{}");
        ELASTICSEARCH_PROXY.setConnectionCut(true);
        try {
            searchOutbox.dispatchOnce(10, Duration.ofSeconds(2));
            assertThat(status("search_outbox", event)).isIn("NEW", "PUBLISHING");
        } finally {
            ELASTICSEARCH_PROXY.setConnectionCut(false);
        }
        jdbc.update("UPDATE search_outbox SET available_at=CURRENT_TIMESTAMP(6),lease_until=NULL WHERE id=?", event.toString());
        searchOutbox.dispatchOnce(10, Duration.ofSeconds(30));
        search.refresh();
        assertThat(status("search_outbox", event)).isEqualTo("PUBLISHED");
        Set<String> mysqlOnSale = Set.copyOf(jdbc.query("SELECT id FROM listing WHERE status='ON_SALE' AND available_quantity>0", (rs, n) -> rs.getString(1)));
        Set<String> indexed = Set.copyOf(search.search(new ProductSearchPort.SearchRequest("故障演练教材", null, null, null, 0, 20)).items().stream().map(ProductSearchPort.SearchItem::listingId).toList());
        assertThat(indexed).contains(listing.toString());
        assertThat(mysqlOnSale).containsAll(indexed);
        assertInvariants();
    }

    @Test
    void round3MinioDisconnectLeavesCleanupPendingThenDeletesAfterRecovery() {
        UUID submitter = user();
        UUID session = UUID.randomUUID();
        jdbc.update("INSERT INTO object_upload_session(id,submitted_by,purpose,object_key,status,expires_at,created_at,updated_at) VALUES (?,?, 'LISTING_MEDIA',?,'OPEN',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 MINUTE),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", session.toString(), submitter.toString(), "cleanup-drill-" + session);
        MINIO_PROXY.setConnectionCut(true);
        try {
            cleanup.runOnce(10);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM storage_cleanup_task WHERE cleanup_business_key=? AND status IN ('PENDING','PROCESSING')", Integer.class, "listing-upload:" + session)).isEqualTo(1);
        } finally {
            MINIO_PROXY.setConnectionCut(false);
        }
        jdbc.update("UPDATE storage_cleanup_task SET run_after=CURRENT_TIMESTAMP(6),lease_until=NULL WHERE cleanup_business_key=?", "listing-upload:" + session);
        cleanup.runOnce(10);
        assertThat(jdbc.queryForObject("SELECT status FROM storage_cleanup_task WHERE cleanup_business_key=?", String.class, "listing-upload:" + session)).isEqualTo("COMPLETED");
        assertInvariants();
    }

    private void insertOutbox(UUID event, String type, UUID aggregate) {
        jdbc.update("INSERT INTO integration_outbox(id,event_id,event_type,aggregate_id,aggregate_version,schema_version,payload,status,attempt_count,available_at,created_at) VALUES (?,?,?, ?,1,1,CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", event.toString(), event.toString(), type, aggregate.toString(), "{}");
    }
    private UUID user() { UUID id = UUID.randomUUID(); jdbc.update("INSERT INTO campus_user(id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", id.toString(), id + "@stu.example.edu.cn", "hash"); return id; }
    private String status(String table, UUID id) { return jdbc.queryForObject("SELECT status FROM " + table + " WHERE id=?", String.class, id.toString()); }
    private void assertInvariants() {
        assertThat(jdbc.queryForObject("SELECT COALESCE(MIN(available_quantity),0) FROM listing", Integer.class)).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT COALESCE(MIN(quarantined_quantity),0) FROM listing", Integer.class)).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT business_key FROM inventory_movement GROUP BY business_key HAVING COUNT(*)>1) duplicates", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_order WHERE successful_refund_fen+reserved_refund_fen>paid_amount_fen", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement s JOIN settlement s2 ON s.order_id=s2.order_id AND s.id<>s2.id", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE status='PUBLISHING' AND lease_until<=CURRENT_TIMESTAMP(6)", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event WHERE status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP(6)", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispute_evidence e LEFT JOIN dispute_case c ON c.id=e.dispute_case_id WHERE c.id IS NULL", Integer.class)).isZero();
    }
}
