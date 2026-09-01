package com.example.campusmarket.integration;

import com.example.campusmarket.catalog.search.ProductSearchPort;
import com.example.campusmarket.catalog.search.ElasticsearchProductSearch;
import com.example.campusmarket.catalog.search.SearchRebuildService;
import com.example.campusmarket.catalog.search.SearchProjector;
import com.example.campusmarket.catalog.search.SearchOutboxDispatcher;
import com.example.campusmarket.catalog.search.SearchGateRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ActiveProfiles("local")
class SearchRebuildIT extends SharedContainers {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductSearchPort search;
    @Autowired SearchProjector projector;
    @Autowired SearchRebuildService rebuild;
    @Autowired SearchOutboxDispatcher dispatcher;
    @Autowired ElasticsearchProductSearch elasticsearch;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
    }

    @BeforeEach
    void fixture() {
        jdbc.update("UPDATE search_rebuild_gate SET mode='OPEN',owner_id=NULL,claim_token=NULL,lease_until=NULL WHERE id=1");
        jdbc.update("DELETE FROM inventory_movement");
        jdbc.update("DELETE FROM payment_callback_event");
        jdbc.update("DELETE FROM refund_order");
        jdbc.update("DELETE FROM settlement");
        jdbc.update("DELETE FROM payment_order");
        jdbc.update("DELETE FROM order_deadline_claim");
        jdbc.update("DELETE FROM order_transition");
        jdbc.update("DELETE FROM order_command");
        jdbc.update("DELETE FROM trade_review");
        jdbc.update("DELETE FROM handoff_record");
        jdbc.update("DELETE FROM dispute_evidence");
        jdbc.update("DELETE FROM return_case");
        jdbc.update("DELETE FROM seller_obligation");
        jdbc.update("DELETE FROM warranty_case");
        jdbc.update("DELETE FROM dispute_case");
        jdbc.update("DELETE FROM trade_order");
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM object_upload_session");
        jdbc.update("DELETE FROM email_verification");
        jdbc.update("DELETE FROM external_identity");
        jdbc.update("DELETE FROM listing_media");
        jdbc.update("DELETE FROM search_outbox");
        jdbc.update("DELETE FROM listing");
        jdbc.update("DELETE FROM campus_user");
        UUID seller = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_user(id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            seller.toString(), seller + "@stu.example.edu.cn", "hash");
        UUID listing = UUID.randomUUID();
        jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,quarantined_quantity,warranty_days,warranty_scope,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,0,NULL,NULL,'ON_SALE',?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            listing.toString(), seller.toString(), "重建并发商品", "在线重建", "教材", 2000L, 4, 1L);
        jdbc.update("INSERT INTO search_outbox(id,listing_id,aggregate_version,event_type,payload,status,attempt_count,available_at,created_at) VALUES (?,?,?,'LISTING_UPDATED',CAST('{}' AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            UUID.randomUUID().toString(), listing.toString(), 1L);
    }

    @Test
    void expiredLeaseCanBeTakenOverAndStaleOwnerCannotReleaseOrRenew() {
        SearchGateRepository firstCoordinator = new SearchGateRepository(jdbc);
        SearchGateRepository secondCoordinator = new SearchGateRepository(jdbc);
        SearchGateRepository.Lease old = firstCoordinator.acquire("rebuild-old", java.time.Duration.ofMinutes(1));
        jdbc.update("UPDATE search_rebuild_gate SET lease_until=TIMESTAMPADD(MICROSECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=1");

        SearchGateRepository.Lease current = secondCoordinator.acquire("rebuild-current", java.time.Duration.ofMinutes(1));
        firstCoordinator.release(old);

        assertThat(jdbc.queryForObject("SELECT owner_id FROM search_rebuild_gate WHERE id=1", String.class))
            .isEqualTo("rebuild-current");
        assertThat(firstCoordinator.renew(old, java.time.Duration.ofMinutes(1))).isFalse();
        assertThat(secondCoordinator.renew(current, java.time.Duration.ofMinutes(1))).isTrue();
        secondCoordinator.release(current);
        assertThat(jdbc.queryForObject("SELECT mode FROM search_rebuild_gate WHERE id=1", String.class)).isEqualTo("OPEN");
    }

    @Test
    void dispatchesEnqueuedChangeThroughClaimProjectAndPublish() {
        assertThat(dispatcher.dispatchOnce(10)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM search_outbox LIMIT 1", String.class)).isEqualTo("PUBLISHED");
    }

    @Test
    void gateClosedDefersWithoutConsumingDeliveryAttempt() {
        SearchGateRepository coordinator = new SearchGateRepository(jdbc);
        SearchGateRepository.Lease lease = coordinator.acquire("rebuild-barrier", java.time.Duration.ofMinutes(1));
        assertThat(dispatcher.dispatchOnce(10)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM search_outbox LIMIT 1", String.class)).isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM search_outbox LIMIT 1", Integer.class)).isZero();
        coordinator.release(lease);
    }

    @Test
    void restoresEsProxyAfterDisconnectAndRerunsWithoutChangingOldAliasOnFailure() {
        search.refresh();
        String previous = elasticsearch.currentReadIndex();
        try {
            ELASTICSEARCH_PROXY.setConnectionCut(true);
            assertThrows(RuntimeException.class, search::refresh);
        } finally {
            ELASTICSEARCH_PROXY.setConnectionCut(false);
        }
        SearchRebuildService.RebuildReport recovered = rebuild.rebuild();
        search.refresh();
        assertThat(recovered.index()).isNotBlank();
        assertThat(elasticsearch.currentReadIndex()).isNotEqualTo(previous);
    }

    @Test
    void switchesAliasesAtomicallyAndThreeRunsDoNotRegressVersions() {
        SearchRebuildService.RebuildReport first = rebuild.rebuild();
        search.refresh();
        ProductSearchPort.SearchPage firstPage = search.search(new ProductSearchPort.SearchRequest("重建", null, null, null, 0, 20));
        SearchRebuildService.RebuildReport second = rebuild.rebuild();
        SearchRebuildService.RebuildReport third = rebuild.rebuild();
        search.refresh();
        ProductSearchPort.SearchPage finalPage = search.search(new ProductSearchPort.SearchRequest("重建", null, null, null, 0, 20));

        assertThat(first.index()).isNotEqualTo(second.index()).isNotEqualTo(third.index());
        assertThat(firstPage.items()).hasSize(1);
        assertThat(finalPage.items()).extracting(ProductSearchPort.SearchItem::listingId)
            .containsExactlyElementsOf(firstPage.items().stream().map(ProductSearchPort.SearchItem::listingId).toList());
        assertThat(finalPage.items()).extracting(ProductSearchPort.SearchItem::aggregateVersion)
            .containsExactly(1L);
        assertThat(jdbc.queryForObject("SELECT status FROM search_index_cleanup_task WHERE index_name=?", String.class, third.index()))
            .isEqualTo("DONE");
    }
}
