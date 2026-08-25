package com.example.search.integration;

import com.example.search.application.maintenance.SearchIndexBootstrap;
import com.example.search.application.product.CreateProductCommand;
import com.example.search.application.product.ProductCommandService;
import com.example.search.application.sync.OutboxDispatcher;
import com.example.search.application.sync.SearchOutboxRepository;
import com.example.search.domain.ProductDetails;
import com.example.search.domain.ProductStatus;
import io.micrometer.core.instrument.MeterRegistry;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"search.batch-size=7", "search.lease-duration=12s", "search.request-timeout=2s"})
class ActuatorSearchMetricsIT extends SharedSearchContainers {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired SearchIndexBootstrap bootstrap;
    @Autowired ProductCommandService products;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired TestRestTemplate rest;
    @Autowired SearchOutboxRepository outbox;
    @Autowired MeterRegistry meterRegistry;

    @BeforeEach
    void clean() throws Exception {
        clearTables(dataSource);
        bootstrap.ensureInitialized();
    }

    @Test
    void exposesBoundedSearchSyncMetricsAndJdbcOutboxGauges() {
        double appliedBefore = meterRegistry.counter("search.sync.events", "outcome", "applied").count();
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
        assertThat(meterRegistry.counter("search.sync.events", "outcome", "applied").count() - appliedBefore)
                .isEqualTo(1.0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM search_outbox WHERE status='COMPLETED'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void appliesConfiguredBatchAndLeaseToRuntimeClaims() {
        for (int i = 0; i < 8; i++) {
            products.create(new CreateProductCommand(new ProductDetails("配置商品-" + i, null, "配置描述",
                    "BOOK", "图书", new BigDecimal("12.00"), ProductStatus.ON_SALE)));
        }

        assertThat(dispatcher.dispatchOnce().claimed()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM search_outbox WHERE status='COMPLETED'", Integer.class))
                .isEqualTo(7);
        var claim = outbox.claim("lease-probe", 1).get(0);
        Long remainingMicros = jdbc.queryForObject(
                "SELECT TIMESTAMPDIFF(MICROSECOND, UTC_TIMESTAMP(6), lease_until) FROM search_outbox WHERE event_id=?",
                Long.class, claim.eventId().toString());
        assertThat(remainingMicros).isBetween(10_000_000L, 12_500_000L);
        assertThat(outbox.complete(claim.eventId(), claim.claimToken())).isTrue();
    }
}
