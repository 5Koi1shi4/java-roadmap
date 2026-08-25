package com.example.search.integration;

import com.example.search.application.maintenance.SearchIndexBootstrap;
import com.example.search.application.product.CreateProductCommand;
import com.example.search.application.product.ProductCommandService;
import com.example.search.application.sync.OutboxDispatcher;
import com.example.search.domain.ProductDetails;
import com.example.search.domain.ProductStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorSearchMetricsIT extends SharedSearchContainers {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired SearchIndexBootstrap bootstrap;
    @Autowired ProductCommandService products;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired TestRestTemplate rest;

    @BeforeEach
    void clean() throws Exception {
        clearTables(dataSource);
        bootstrap.ensureInitialized();
    }

    @Test
    void exposesBoundedSearchSyncMetricsAndJdbcOutboxGauges() {
        products.create(new CreateProductCommand(new ProductDetails("指标商品", null, "指标描述",
                "BOOK", "图书", new BigDecimal("12.00"), ProductStatus.ON_SALE)));
        dispatcher.dispatchOnce();
        assertThat(rest.getForEntity("/api/products/search?q=指标", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        List<String> names = List.of("search.sync.events", "search.sync.bulk.duration",
                "search.sync.outbox", "search.sync.oldest.age", "search.query.duration",
                "search.query.errors", "search.rebuild.duration", "search.rebuild.differences");
        for (String name : names) {
            assertThat(rest.getForEntity("/actuator/metrics/" + name, String.class).getStatusCode())
                    .as(name).isEqualTo(HttpStatus.OK);
        }

        String outbox = rest.getForObject("/actuator/metrics/search.sync.outbox", String.class);
        assertThat(outbox).contains("status", "NEW", "PROCESSING", "COMPLETED", "FAILED")
                .doesNotContain("eventId", "productId", "last_error");
        assertThat(rest.getForObject("/actuator/metrics/search.query.duration?tag=outcome:success", String.class))
                .contains("\"statistic\":\"COUNT\",\"value\":1.0");
        assertThat(rest.getForObject("/actuator/metrics/search.sync.events?tag=outcome:applied", String.class))
                .contains("\"statistic\":\"COUNT\",\"value\":1.0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM search_outbox WHERE status='COMPLETED'", Integer.class))
                .isEqualTo(1);
    }
}
