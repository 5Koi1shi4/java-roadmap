package com.example.search.integration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import com.example.search.application.maintenance.ConsistencyReport;
import com.example.search.application.maintenance.SearchConsistencyService;
import com.example.search.application.maintenance.SearchIndexBootstrap;
import com.example.search.application.product.CreateProductCommand;
import com.example.search.application.product.ProductCommandService;
import com.example.search.application.product.ProductView;
import com.example.search.application.sync.OutboxClaimService;
import com.example.search.application.sync.OutboxDispatcher;
import com.example.search.domain.ProductDetails;
import com.example.search.domain.ProductStatus;
import com.example.search.infrastructure.elasticsearch.ElasticsearchIndexManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "search.maintenance.enabled=true")
class SearchMaintenanceIT extends SharedSearchContainers {
    @Autowired DataSource dataSource;
    @Autowired ProductCommandService products;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired OutboxClaimService claims;
    @Autowired SearchConsistencyService consistency;
    @Autowired SearchIndexBootstrap bootstrap;
    @Autowired ElasticsearchIndexManager indexes;
    @Autowired ElasticsearchClient client;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate rest;

    @BeforeEach
    void clean() throws Exception {
        clearTables(dataSource);
        bootstrap.ensureInitialized();
        client.deleteByQuery(d -> d.index("products-write").query(q -> q.matchAll(m -> m)));
        indexes.refresh("products-write");
    }

    @Test
    void reportsMissingStaleAndOrphanAndRepairsThroughOutbox() throws Exception {
        ProductView missing = createAndSync("缺失");
        ProductView stale = createAndSync("落后");
        createAndSync("正常");
        client.delete(d -> d.index("products-write").id(Long.toString(missing.id()))
                .version(missing.version()).versionType(VersionType.ExternalGte));
        client.update(u -> u.index("products-write").id(Long.toString(stale.id()))
                .script(s -> s.source("ctx._source.sourceVersion = 2")), Map.class);
        client.index(i -> i.index("products-write").id("999999").document(orphan(999999L)));
        indexes.refresh("products-write");

        ConsistencyReport report = consistency.check();

        assertThat(report.missingCount()).isEqualTo(1);
        assertThat(report.staleCount()).isEqualTo(1);
        assertThat(report.orphanCount()).isEqualTo(1);
        assertThat(report.missingProductIds()).containsExactly(missing.id());
        consistency.repairProduct(missing.id());
        dispatcher.dispatchOnce();
        indexes.refresh("products-write");
        ConsistencyReport repaired = consistency.check();
        assertThat(repaired.missingCount()).isZero();
        assertThat(repaired.staleCount()).isEqualTo(1);
        assertThat(repaired.orphanCount()).isEqualTo(1);
    }

    @Test
    void capsSamplesAndRetriesOnlyFailedOutboxEvents() throws Exception {
        ProductView product = products.create(new CreateProductCommand(details("失败重投")));
        var claim = claims.claim("maintenance-it", 1).get(0);
        assertThat(claims.fail(claim.eventId(), claim.claimToken(), "kept diagnostic")).isTrue();

        consistency.retryFailed(claim.eventId());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status,attempt_count,owner,claim_token,lease_until,last_error FROM search_outbox WHERE event_id=?",
                claim.eventId().toString());
        assertThat(row.get("status")).isEqualTo("NEW");
        assertThat(row.get("attempt_count")).isEqualTo(0);
        assertThat(row.get("owner")).isNull();
        assertThat(row.get("claim_token")).isNull();
        assertThat(row.get("lease_until")).isNull();
        assertThat(row.get("last_error")).isEqualTo("kept diagnostic");
        assertThatThrownBy(() -> consistency.retryFailed(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);

        for (long id = 10_000; id < 10_025; id++) {
            long productId = id;
            client.index(i -> i.index("products-write").id(Long.toString(productId)).document(orphan(productId)));
        }
        indexes.refresh("products-write");
        ConsistencyReport report = consistency.check();
        assertThat(report.orphanCount()).isEqualTo(25);
        assertThat(report.orphanProductIds()).hasSize(20);
    }

    @Test
    void enabledMaintenanceApiUsesFixedSafeRoutes() {
        ResponseEntity<String> check = rest.postForEntity("/api/admin/search/consistency-checks", null, String.class);
        assertThat(check.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(check.getBody()).contains("missingCount", "staleCount", "orphanCount");
        assertThat(rest.postForEntity("/api/admin/search/products/-1/repair", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.postForEntity("/api/admin/search/outbox/not-a-uuid/retry", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/admin/search/raw-dsl", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ProductView createAndSync(String name) {
        ProductView product = products.create(new CreateProductCommand(details(name)));
        dispatcher.dispatchOnce();
        indexes.refresh("products-write");
        return product;
    }

    private static ProductDetails details(String name) {
        return new ProductDetails(name, null, "维护测试", "BOOK", "图书",
                new BigDecimal("10.00"), ProductStatus.ON_SALE);
    }

    private static Map<String, Object> orphan(long id) {
        return Map.ofEntries(Map.entry("productId", id), Map.entry("name", "orphan-" + id),
                Map.entry("description", "orphan"), Map.entry("categoryCode", "BOOK"),
                Map.entry("categoryName", "图书"), Map.entry("price", 1.0),
                Map.entry("status", "ON_SALE"), Map.entry("sourceVersion", 1L),
                Map.entry("createdAt", "2026-01-01T00:00:00Z"),
                Map.entry("updatedAt", "2026-01-01T00:00:00Z"));
    }
}
