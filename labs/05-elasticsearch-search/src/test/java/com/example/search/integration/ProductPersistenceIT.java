package com.example.search.integration;

import com.example.search.application.product.CreateProductCommand;
import com.example.search.application.product.ProductCommandService;
import com.example.search.application.product.ProductNotFoundException;
import com.example.search.application.product.ProductVersionConflictException;
import com.example.search.application.product.ProductView;
import com.example.search.application.product.UpdateProductCommand;
import com.example.search.domain.ProductDetails;
import com.example.search.domain.ProductStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ProductPersistenceIT extends SharedMySqlContainer {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductCommandService service;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void clean() throws Exception { clearTables(dataSource); }

    @Test
    void commitsProductAndOutboxInOneTransaction() throws Exception {
        ProductView created = service.create(validCreateCommand());
        assertThat(created.version()).isEqualTo(1);
        assertThat(outbox(created.id())).singleElement().satisfies(row -> {
            assertThat(row.get("product_version")).isEqualTo(1L);
            assertThat(row.get("event_type")).isEqualTo("PRODUCT_UPSERT");
        });
        assertSnapshot(payload(created.id(), "PRODUCT_UPSERT"), created.id(), 1, "书", "ON_SALE");
    }

    @Test
    void updatesAndDeletesWithMonotonicVersions() throws Exception {
        ProductView created = service.create(validCreateCommand());
        ProductView updated = service.update(created.id(), new UpdateProductCommand(1, details("改名")));
        assertThat(updated.version()).isEqualTo(2);
        service.delete(created.id(), 2);
        assertThat(jdbc.queryForObject("SELECT status FROM product WHERE id=?", String.class, created.id())).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("SELECT version FROM product WHERE id=?", Long.class, created.id())).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM search_outbox WHERE product_id=?", Long.class, created.id())).isEqualTo(3L);
        assertSnapshot(payload(created.id(), "PRODUCT_DELETE"), created.id(), 3, "改名", "DELETED");
    }

    @Test
    void rejectsStaleVersion() {
        ProductView created = service.create(validCreateCommand());
        service.update(created.id(), new UpdateProductCommand(1, details("一次更新")));
        assertThatThrownBy(() -> service.update(created.id(), new UpdateProductCommand(1, details("过期更新"))))
                .isInstanceOf(ProductVersionConflictException.class);
        assertThat(jdbc.queryForObject("SELECT version FROM product WHERE id=?", Long.class, created.id())).isEqualTo(2L);
    }

    @Test
    void concurrentUpdatesWithSameVersionHaveOneWinnerAndOneConflict() throws Exception {
        ProductView created = service.create(validCreateCommand());
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Callable<Object> attempt = () -> {
            start.await();
            try {
                return service.update(created.id(), new UpdateProductCommand(1, details("并发更新")));
            } catch (RuntimeException failure) {
                return failure;
            }
        };
        List<Future<Object>> futures = executor.invokeAll(List.of(attempt, attempt));
        executor.shutdownNow();
        List<Object> results = List.of(futures.get(0).get(), futures.get(1).get());
        assertThat(results.stream().filter(ProductView.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(ProductVersionConflictException.class::isInstance)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT version FROM product WHERE id=?", Long.class, created.id())).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM search_outbox WHERE product_id=? AND product_version=2 AND event_type='PRODUCT_UPSERT'", Long.class, created.id())).isEqualTo(1L);
    }

    @Test
    void distinguishesMissingAndDeletedProductsForMutations() {
        assertThatThrownBy(() -> service.update(999_999L, new UpdateProductCommand(1, details("不存在"))))
                .isInstanceOf(ProductNotFoundException.class);
        assertThatThrownBy(() -> service.delete(999_999L, 1))
                .isInstanceOf(ProductNotFoundException.class);

        ProductView created = service.create(validCreateCommand());
        service.delete(created.id(), 1);
        assertThatThrownBy(() -> service.update(created.id(), new UpdateProductCommand(2, details("已删除"))))
                .isInstanceOf(ProductVersionConflictException.class);
        assertThatThrownBy(() -> service.delete(created.id(), 2))
                .isInstanceOf(ProductVersionConflictException.class);
    }

    @Test
    void rollsProductUpdateBackWhenOutboxUniqueKeyRejectsEvent() {
        ProductView created = service.create(validCreateCommand());
        jdbc.update("INSERT INTO search_outbox(event_id, product_id, product_version, event_type, payload, status, available_at, created_at) VALUES (?, ?, 2, 'PRODUCT_UPSERT', '{}', 'NEW', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                UUID.randomUUID().toString(), created.id());
        assertThatThrownBy(() -> service.update(created.id(), new UpdateProductCommand(1, details("必须回滚"))))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        List<String> rows = jdbc.query("SELECT version, name FROM product WHERE id=?", (RowMapper<String>) (rs, n) -> {
            return rs.getLong("version") + ":" + rs.getString("name");
        }, created.id());
        assertThat(rows).containsExactly("1:书");
    }

    private CreateProductCommand validCreateCommand() { return new CreateProductCommand(details("书")); }

    private ProductDetails details(String name) {
        return new ProductDetails(name, null, "教材", "BOOK", "图书", new BigDecimal("10.00"), ProductStatus.ON_SALE);
    }

    private List<Map<String, Object>> outbox(long productId) {
        return jdbc.queryForList("SELECT product_version, event_type, payload FROM search_outbox WHERE product_id=? ORDER BY id", productId);
    }

    private JsonNode payload(long productId, String eventType) throws Exception {
        String json = jdbc.queryForObject("SELECT payload FROM search_outbox WHERE product_id=? AND event_type=? ORDER BY id DESC LIMIT 1", String.class, productId, eventType);
        return objectMapper.readTree(json);
    }

    private void assertSnapshot(JsonNode payload, long productId, long version, String name, String status) {
        assertThat(payload.get("productId").asLong()).isEqualTo(productId);
        assertThat(payload.get("sourceVersion").asLong()).isEqualTo(version);
        assertThat(payload.get("name").asText()).isEqualTo(name);
        assertThat(payload.has("subtitle")).isTrue();
        assertThat(payload.get("subtitle").isNull()).isTrue();
        assertThat(payload.get("description").asText()).isEqualTo("教材");
        assertThat(payload.get("categoryCode").asText()).isEqualTo("BOOK");
        assertThat(payload.get("categoryName").asText()).isEqualTo("图书");
        assertThat(payload.get("price").decimalValue()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(payload.get("status").asText()).isEqualTo(status);
        assertThat(payload.get("createdAt").asText()).isNotBlank();
        assertThat(payload.get("updatedAt").asText()).isNotBlank();
    }
}
