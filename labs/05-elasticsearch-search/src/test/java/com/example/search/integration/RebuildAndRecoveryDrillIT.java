package com.example.search.integration;

import com.example.search.application.maintenance.ConsistencyReport;
import com.example.search.application.maintenance.RebuildStatus;
import com.example.search.application.maintenance.RebuildProgressListener;
import com.example.search.application.maintenance.SearchRebuildService;
import com.example.search.application.maintenance.SearchConsistencyService;
import com.example.search.application.maintenance.SearchIndexBootstrap;
import com.example.search.application.product.CreateProductCommand;
import com.example.search.application.product.ProductCommandService;
import com.example.search.application.product.ProductView;
import com.example.search.application.product.UpdateProductCommand;
import com.example.search.domain.ProductDetails;
import com.example.search.domain.ProductStatus;
import com.example.search.infrastructure.elasticsearch.ElasticsearchIndexManager;
import com.example.search.observability.OutboxScheduler;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "search.maintenance.enabled=true")
@Import(RebuildAndRecoveryDrillIT.HookConfiguration.class)
class RebuildAndRecoveryDrillIT extends SharedScheduledSearchContainers {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired SearchIndexBootstrap bootstrap;
    @Autowired ProductCommandService products;
    @Autowired SearchRebuildService rebuilds;
    @Autowired SearchConsistencyService consistency;
    @Autowired ElasticsearchIndexManager indexes;
    @Autowired TestRestTemplate rest;
    @Autowired HookState hook;
    @Autowired OutboxScheduler scheduler;
    @Autowired AtomicBoolean searchStartupReady;

    @BeforeEach
    void clean() throws Exception {
        assertThat(scheduler).isNotNull();
        assertThat(searchStartupReady).isTrue();
        setElasticConnectionCut(false);
        clearTables(dataSource);
        bootstrap.ensureInitialized();
        hook.clear();
    }

    @Test
    void repeatsThreeRebuildsAndTwoConnectionOutageRecoveriesWithoutRegression() {
        for (int round = 1; round <= 3; round++) {
            ProductView before = products.create(new CreateProductCommand(details("重建前-" + round)));
            String updatedName = "重建中-" + round;
            hook.onSnapshot = startWatermark -> updateAfterSnapshot(before, updatedName, startWatermark);
            var jobId = rebuilds.startRebuild();
            Awaitility.await().atMost(Duration.ofSeconds(45)).untilAsserted(() ->
                    assertThat(rebuilds.getRebuild(jobId).status()).isEqualTo(RebuildStatus.COMPLETED));
            var completed = rebuilds.getRebuild(jobId);
            long updateEventId = hook.updateEventId.get();
            // The hook joins the committed mutation before prepare samples its later prepared watermark.
            assertThat(updateEventId).isGreaterThan(completed.startWatermark());
            assertThat(completed.finalWatermark()).isGreaterThanOrEqualTo(updateEventId);
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

    private void updateAfterSnapshot(ProductView before, String updatedName, long startWatermark) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread mutation = new Thread(() -> {
            try {
                products.update(before.id(), new UpdateProductCommand(before.version(), details(updatedName)));
                Long eventId = jdbc.queryForObject(
                        "SELECT id FROM search_outbox WHERE product_id=? AND product_version=?",
                        Long.class, before.id(), before.version() + 1);
                hook.startWatermark.set(startWatermark);
                hook.updateEventId.set(eventId == null ? 0 : eventId);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "rebuild-drill-mutation");
        mutation.start();
        try {
            mutation.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("rebuild mutation interrupted", interrupted);
        }
        if (failure.get() != null) throw new IllegalStateException("rebuild mutation failed", failure.get());
    }

    private static ProductDetails details(String name) {
        return new ProductDetails(name, null, "恢复演练", "BOOK", "图书",
                new BigDecimal("20.00"), ProductStatus.ON_SALE);
    }

    static final class HookState {
        private java.util.function.LongConsumer onSnapshot;
        private final AtomicLong startWatermark = new AtomicLong();
        private final AtomicLong updateEventId = new AtomicLong();

        void clear() {
            onSnapshot = null;
            startWatermark.set(0);
            updateEventId.set(0);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class HookConfiguration {
        @Bean HookState rebuildDrillHookState() { return new HookState(); }

        @Bean
        RebuildProgressListener rebuildDrillProgressListener(HookState hook) {
            return (jobId, watermark) -> {
                var action = hook.onSnapshot;
                hook.onSnapshot = null;
                if (action != null) action.accept(watermark);
            };
        }
    }
}
