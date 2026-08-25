package com.example.search.integration;

import com.example.search.application.maintenance.ConsistencyReport;
import com.example.search.application.maintenance.RebuildStatus;
import com.example.search.application.maintenance.SearchConsistencyService;
import com.example.search.application.maintenance.SearchIndexBootstrap;
import com.example.search.application.maintenance.SearchRebuildService;
import com.example.search.application.product.CreateProductCommand;
import com.example.search.application.product.ProductCommandService;
import com.example.search.application.product.ProductView;
import com.example.search.application.product.UpdateProductCommand;
import com.example.search.domain.ProductDetails;
import com.example.search.domain.ProductStatus;
import com.example.search.infrastructure.elasticsearch.ElasticsearchIndexManager;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "search.maintenance.enabled=true")
class RebuildAndRecoveryDrillIT extends SharedSearchContainers {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired SearchIndexBootstrap bootstrap;
    @Autowired ProductCommandService products;
    @Autowired SearchRebuildService rebuilds;
    @Autowired SearchConsistencyService consistency;
    @Autowired ElasticsearchIndexManager indexes;
    @Autowired TestRestTemplate rest;

    @BeforeEach
    void clean() throws Exception {
        setElasticConnectionCut(false);
        clearTables(dataSource);
        bootstrap.ensureInitialized();
    }

    @Test
    void repeatsThreeRebuildsAndTwoConnectionOutageRecoveriesWithoutRegression() {
        for (int round = 1; round <= 3; round++) {
            ProductView before = products.create(new CreateProductCommand(details("重建前-" + round)));
            var jobId = rebuilds.startRebuild();
            products.update(before.id(), new UpdateProductCommand(before.version(), details("重建中-" + round)));

            Awaitility.await().atMost(Duration.ofSeconds(45)).untilAsserted(() ->
                    assertThat(rebuilds.getRebuild(jobId).status()).isEqualTo(RebuildStatus.COMPLETED));
            awaitCaughtUp();
        }

        for (int round = 1; round <= 2; round++) {
            String finalName = "故障演练-" + round;
            try {
                setElasticConnectionCut(true);
                assertThat(rest.getForEntity("/api/products/search?q=故障演练", String.class).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                ProductView created = products.create(new CreateProductCommand(details("断连创建-" + round)));
                products.update(created.id(), new UpdateProductCommand(created.version(), details(finalName)));
                Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                        assertThat(unfinishedOutbox()).isGreaterThanOrEqualTo(2));
            } finally {
                setElasticConnectionCut(false);
            }
            awaitCaughtUp();
            var recovered = rest.getForEntity("/api/products/search?q=" + finalName, String.class);
            assertThat(recovered.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(recovered.getBody()).contains(finalName);
        }
        assertThat(rest.getForObject(
                "/actuator/metrics/search.rebuild.duration?tag=outcome:completed", String.class))
                .contains("\"statistic\":\"COUNT\",\"value\":3.0");
        assertThat(rest.getForObject("/actuator/metrics/search.query.errors", String.class))
                .contains("\"statistic\":\"COUNT\",\"value\":2.0");
    }

    private void awaitCaughtUp() {
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(unfinishedOutbox()).isZero());
        indexes.refresh("products-write");
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            ConsistencyReport report = consistency.check();
            assertThat(report.missingCount()).isZero();
            assertThat(report.staleCount()).isZero();
            assertThat(report.orphanCount()).isZero();
        });
    }

    private int unfinishedOutbox() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM search_outbox WHERE status <> 'COMPLETED'", Integer.class);
    }

    private static ProductDetails details(String name) {
        return new ProductDetails(name, null, "恢复演练", "BOOK", "图书",
                new BigDecimal("20.00"), ProductStatus.ON_SALE);
    }
}
