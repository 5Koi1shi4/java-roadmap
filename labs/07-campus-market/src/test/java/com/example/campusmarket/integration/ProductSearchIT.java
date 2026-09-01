package com.example.campusmarket.integration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.AnalyzeResponse;
import com.example.campusmarket.catalog.search.ProductSearchPort;
import com.example.campusmarket.catalog.search.SearchProjector;
import com.example.campusmarket.shared.DomainEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.flywaydb.core.Flyway;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("local")
class ProductSearchIT extends SharedContainers {
    @Autowired JdbcTemplate jdbc;
    @Autowired ElasticsearchClient elasticsearch;
    @Autowired ProductSearchPort search;
    @Autowired SearchProjector projector;

    private UUID onSale;
    private UUID offSale;
    private UUID soldOut;
    private static final UUID ON_SALE_ID = UUID.fromString("00000000-0000-0000-0000-000000000701");
    private static final UUID OFF_SALE_ID = UUID.fromString("00000000-0000-0000-0000-000000000702");
    private static final UUID SOLD_OUT_ID = UUID.fromString("00000000-0000-0000-0000-000000000703");

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
    }

    @BeforeEach
    void fixture() {
        jdbc.update("DELETE FROM listing_media");
        jdbc.update("DELETE FROM listing");
        jdbc.update("DELETE FROM campus_user");
        UUID seller = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_user(id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            seller.toString(), seller + "@stu.example.edu.cn", "hash");
        onSale = insertListing(ON_SALE_ID, seller, "Java 并发编程实战", "并发编程与线程池", "教材", 18900, 3, "ON_SALE", 3);
        offSale = insertListing(OFF_SALE_ID, seller, "并发编程旧版", "Java 并发编程", "教材", 9900, 2, "OFF_SALE", 4);
        soldOut = insertListing(SOLD_OUT_ID, seller, "Java 并发编程售罄", "并发编程", "教材", 12900, 0, "SOLD_OUT", 5);
        projector.project(event(onSale, 3));
        projector.project(event(offSale, 4));
        projector.project(event(soldOut, 5));
        search.refresh();
    }

    @Test
    void usesSmartCnAndReturnsOnlyAvailableOnSaleListings() throws Exception {
        AnalyzeResponse analyzed = elasticsearch.indices().analyze(a -> a.index(ProductSearchPort.READ_ALIAS)
            .analyzer("smartcn").text("并发编程实战"));
        assertThat(analyzed.tokens()).extracting(t -> t.token()).contains("并发", "编程");

        ProductSearchPort.SearchPage page = search.search(new ProductSearchPort.SearchRequest(
            "并发编程", "教材", null, null, 0, 20));
        assertThat(page.items()).extracting(ProductSearchPort.SearchItem::listingId)
            .containsExactly(onSale.toString());
    }

    @Test
    void filtersIntegerFenAndHasDeterministicPaging() {
        ProductSearchPort.SearchPage page = search.search(new ProductSearchPort.SearchRequest(
            "", "教材", 18_000L, 19_000L, 0, 1));
        assertThat(page.items()).hasSize(1).first().extracting(ProductSearchPort.SearchItem::unitPriceFen)
            .isEqualTo(18_900L);
        assertThat(page.nextSearchAfter()).isNull();
    }

    private UUID insertListing(UUID id, UUID seller, String title, String description, String category, long price,
                               int quantity, String status, long version) {
        jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,quarantined_quantity,warranty_days,warranty_scope,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,0,NULL,NULL,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            id.toString(), seller.toString(), title, description, category, price, quantity, status, version);
        return id;
    }

    private static DomainEvent event(UUID id, long version) {
        return new DomainEvent(UUID.randomUUID(), "LISTING_UPDATED", id.toString(), version, Instant.now(), 1,
            Map.of());
    }
}
