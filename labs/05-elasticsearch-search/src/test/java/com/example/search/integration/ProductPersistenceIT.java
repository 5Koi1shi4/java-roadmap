package com.example.search.integration;

import com.example.search.application.product.CreateProductCommand;
import com.example.search.application.product.ProductCommandService;
import com.example.search.application.product.ProductVersionConflictException;
import com.example.search.application.product.ProductView;
import com.example.search.application.product.UpdateProductCommand;
import com.example.search.domain.ProductDetails;
import com.example.search.domain.ProductStatus;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ProductPersistenceIT extends SharedMySqlContainer {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductCommandService service;

    @BeforeEach
    void clean() throws Exception { clearTables(dataSource); }

    @Test
    void commitsProductAndOutboxInOneTransaction() {
        ProductView created = service.create(validCreateCommand());
        assertThat(created.version()).isEqualTo(1);
        assertThat(outbox(created.id())).singleElement().satisfies(row -> {
            assertThat(row.get("product_version")).isEqualTo(1L);
            assertThat(row.get("event_type")).isEqualTo("PRODUCT_UPSERT");
        });
    }

    @Test
    void updatesAndDeletesWithMonotonicVersions() {
        ProductView created = service.create(validCreateCommand());
        ProductView updated = service.update(created.id(), new UpdateProductCommand(1, details("改名")));
        assertThat(updated.version()).isEqualTo(2);
        service.delete(created.id(), 2);
        assertThat(jdbc.queryForObject("SELECT status FROM product WHERE id=?", String.class, created.id())).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("SELECT version FROM product WHERE id=?", Long.class, created.id())).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM search_outbox WHERE product_id=?", Long.class, created.id())).isEqualTo(3L);
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
}
