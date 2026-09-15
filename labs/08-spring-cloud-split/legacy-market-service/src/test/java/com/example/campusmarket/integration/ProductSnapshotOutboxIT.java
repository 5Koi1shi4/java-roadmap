package com.example.campusmarket.integration;

import com.example.campusmarket.catalog.application.InventoryPort;
import com.example.campusmarket.catalog.application.ListingService;
import com.example.campusmarket.catalog.search.SearchOutboxRepository;
import com.example.campusmarket.legacy.LegacyMarketApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("local")
@SpringBootTest(classes = LegacyMarketApplication.class)
class ProductSnapshotOutboxIT extends SharedContainers {
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private SearchOutboxRepository outbox;
    @Autowired
    private InventoryPort inventory;
    @Autowired
    private ListingService listings;
    @Autowired
    private TransactionTemplate transactions;

    @Test
    void rolledBackFactChangeLeavesNoSnapshotOutboxRow() {
        Fixture fixture = insertListing("ON_SALE", 3, 0, 7);
        int before = outboxCount(fixture.listing());

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.update("UPDATE listing SET status='OFF_SALE',version=8 WHERE id=?", fixture.listing().toString());
            outbox.enqueue(fixture.listing(), 8, "LISTING_UPDATED");
            status.setRollbackOnly();
            throw new IllegalStateException("rollback probe");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT status FROM listing WHERE id=?", String.class,
            fixture.listing().toString())).isEqualTo("ON_SALE");
        assertThat(jdbc.queryForObject("SELECT version FROM listing WHERE id=?", Long.class,
            fixture.listing().toString())).isEqualTo(7);
        assertThat(outboxCount(fixture.listing())).isEqualTo(before);
    }

    @Test
    void publishCapturesCompleteOnSaleSnapshotAtExactVersion() throws Exception {
        Fixture fixture = insertListing("DRAFT", 3, 0, 0);
        jdbc.update("INSERT INTO listing_media(id,listing_id,object_key,media_type,size_bytes,sort_order,created_at) "
                + "VALUES (?,?,?,'image/jpeg',1,0,CURRENT_TIMESTAMP(6))",
            UUID.randomUUID().toString(), fixture.listing().toString(), "private-object-key-" + fixture.listing());

        listings.publish(fixture.seller(), fixture.listing());

        EventRow event = latestEvent(fixture.listing());
        assertThat(event.schemaVersion()).isEqualTo(2);
        assertThat(event.eventType()).isEqualTo("LISTING_PUBLISHED");
        assertThat(event.aggregateVersion()).isEqualTo(1);
        JsonNode payload = mapper.readTree(event.payload());
        assertThat(payload.path("eventId").asText()).isEqualTo(event.id().toString());
        assertThat(payload.path("listingId").asText()).isEqualTo(fixture.listing().toString());
        assertThat(payload.path("aggregateVersion").asLong()).isEqualTo(1);
        assertThat(payload.path("eventType").asText()).isEqualTo("LISTING_PUBLISHED");
        assertThat(payload.path("occurredAt").asText()).isNotBlank();
        assertThat(payload.path("schemaVersion").asInt()).isEqualTo(2);
        JsonNode snapshot = payload.path("snapshot");
        assertThat(snapshot.path("title").asText()).isEqualTo("商品标题");
        assertThat(snapshot.path("description").asText()).isEqualTo("完整商品描述");
        assertThat(snapshot.path("category").asText()).isEqualTo("教材");
        assertThat(snapshot.path("unitPriceFen").asLong()).isEqualTo(12900);
        assertThat(snapshot.path("availableQuantity").asInt()).isEqualTo(3);
        assertThat(snapshot.path("status").asText()).isEqualTo("ON_SALE");
        assertThat(event.payload()).doesNotContain("private-object-key", "sellerId", "warranty", "token");
    }

    @Test
    void inventoryToZeroCapturesSoldOutSnapshotWithNextVersion() throws Exception {
        Fixture fixture = insertListing("DRAFT", 1, 0, 0);
        jdbc.update("INSERT INTO listing_media(id,listing_id,object_key,media_type,size_bytes,sort_order,created_at) "
                + "VALUES (?,?,?,'image/jpeg',1,0,CURRENT_TIMESTAMP(6))",
            UUID.randomUUID().toString(), fixture.listing().toString(), "private-object-key-" + fixture.listing());
        listings.publish(fixture.seller(), fixture.listing());
        assertThat(inventory.deduct(fixture.listing(), 1, "snapshot-zero-" + fixture.listing())).isTrue();

        List<EventRow> events = events(fixture.listing());
        assertThat(events).hasSize(2);
        assertThat(events).extracting(EventRow::aggregateVersion).containsExactly(1L, 2L);
        assertThat(mapper.readTree(events.get(0).payload()).path("snapshot").path("status").asText())
            .isEqualTo("ON_SALE");
        assertThat(mapper.readTree(events.get(0).payload()).path("snapshot").path("availableQuantity").asInt())
            .isEqualTo(1);
        assertThat(mapper.readTree(events.get(1).payload()).path("snapshot").path("status").asText())
            .isEqualTo("SOLD_OUT");
        assertThat(mapper.readTree(events.get(1).payload()).path("snapshot").path("availableQuantity").asInt())
            .isZero();
    }

    @Test
    void quarantineRelistAndScrapEachCaptureNonNegativeSnapshot() throws Exception {
        Fixture fixture = insertListing("ON_SALE", 5, 0, 0);

        assertThat(inventory.quarantine(fixture.listing(), 2, "snapshot-quarantine-" + fixture.listing())).isTrue();
        assertThat(inventory.relistQuarantined(fixture.listing(), 1,
            "snapshot-relist-" + fixture.listing())).isTrue();
        assertThat(inventory.scrapQuarantined(fixture.listing(), 1,
            "snapshot-scrap-" + fixture.listing())).isTrue();

        assertThat(jdbc.queryForObject("SELECT available_quantity FROM listing WHERE id=?", Integer.class,
            fixture.listing().toString())).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT quarantined_quantity FROM listing WHERE id=?", Integer.class,
            fixture.listing().toString())).isZero();
        List<EventRow> events = events(fixture.listing());
        assertThat(events).hasSize(3);
        assertThat(events).extracting(EventRow::aggregateVersion).containsExactly(1L, 2L, 3L);
        assertThat(events).allSatisfy(event -> {
            JsonNode snapshot = read(event).path("snapshot");
            assertThat(snapshot.path("availableQuantity").asInt()).isGreaterThanOrEqualTo(0);
            assertThat(snapshot.path("status").asText()).isEqualTo("ON_SALE");
        });
        assertThat(events.stream().map(event -> read(event).path("snapshot").path("availableQuantity").asInt()).toList())
            .containsExactly(5, 6, 6);
    }

    private JsonNode read(EventRow event) {
        try {
            return mapper.readTree(event.payload());
        } catch (Exception e) {
            throw new AssertionError("快照事件 JSON 无效", e);
        }
    }

    private int outboxCount(UUID listing) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM search_outbox WHERE listing_id=?", Integer.class,
            listing.toString());
    }

    private EventRow latestEvent(UUID listing) {
        return jdbc.queryForObject("SELECT id,aggregate_version,event_type,payload,schema_version "
                + "FROM search_outbox WHERE listing_id=? ORDER BY sequence_no DESC LIMIT 1",
            (rs, rowNum) -> new EventRow(UUID.fromString(rs.getString("id")), rs.getLong("aggregate_version"),
                rs.getString("event_type"), rs.getString("payload"), rs.getInt("schema_version")), listing.toString());
    }

    private List<EventRow> events(UUID listing) {
        return jdbc.query("SELECT id,aggregate_version,event_type,payload,schema_version FROM search_outbox "
                + "WHERE listing_id=? ORDER BY sequence_no",
            (rs, rowNum) -> new EventRow(UUID.fromString(rs.getString("id")), rs.getLong("aggregate_version"),
                rs.getString("event_type"), rs.getString("payload"), rs.getInt("schema_version")), listing.toString());
    }

    private Fixture insertListing(String status, int available, int quarantined, long version) {
        UUID seller = UUID.randomUUID();
        UUID listing = UUID.randomUUID();
        jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,"
                + "quarantined_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,'2026-01-01',"
                + "'2026-01-01')", listing.toString(), seller.toString(), "商品标题", "完整商品描述", "教材", 12900,
            available, quarantined, status, version);
        return new Fixture(seller, listing);
    }

    private record Fixture(UUID seller, UUID listing) { }

    private record EventRow(UUID id, long aggregateVersion, String eventType, String payload, int schemaVersion) { }
}
