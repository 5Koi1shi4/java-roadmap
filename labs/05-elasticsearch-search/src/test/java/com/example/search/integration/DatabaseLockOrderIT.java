package com.example.search.integration;

import com.example.search.application.product.CreateProductCommand;
import com.example.search.application.product.ProductCommandService;
import com.example.search.application.product.ProductView;
import com.example.search.application.product.UpdateProductCommand;
import com.example.search.application.sync.ClaimedOutboxEvent;
import com.example.search.application.sync.OutboxClaimService;
import com.example.search.domain.ProductDetails;
import com.example.search.domain.ProductStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DatabaseLockOrderIT extends SharedMySqlContainer {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired ProductCommandService products;
    @Autowired OutboxClaimService outbox;

    @BeforeEach
    void clean() throws Exception {
        clearTables(dataSource);
    }

    @Test
    void productWritePauseUpdateAndClaimUseOneLockOrder() throws Exception {
        ProductView product = products.create(new CreateProductCommand(details("初始")));
        CyclicBarrier start = new CyclicBarrier(3);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> productWrite = executor.submit(() -> transactionTemplate.execute(status -> {
                await(start);
                products.update(product.id(), new UpdateProductCommand(1, details("更新")));
                return null;
            }));
            Future<?> pauseUpdate = executor.submit(() -> transactionTemplate.execute(status -> {
                await(start);
                jdbc.update("UPDATE search_coordination SET dispatcher_paused = NOT dispatcher_paused WHERE id = 1");
                return null;
            }));
            Future<List<ClaimedOutboxEvent>> claim = executor.submit(() -> transactionTemplate.execute(status -> {
                await(start);
                return outbox.claim("node-a", 50);
            }));

            productWrite.get(5, TimeUnit.SECONDS);
            pauseUpdate.get(5, TimeUnit.SECONDS);
            List<ClaimedOutboxEvent> claimed = claim.get(5, TimeUnit.SECONDS);
            assertThat(productWrite.isDone()).isTrue();
            assertThat(pauseUpdate.isDone()).isTrue();
            assertThat(claim.isDone()).isTrue();
            assertThat(jdbc.queryForObject("SELECT version FROM product WHERE id=?", Long.class, product.id()))
                    .isEqualTo(2L);
            assertThat(jdbc.queryForObject("SELECT dispatcher_paused FROM search_coordination WHERE id=1", Boolean.class))
                    .isTrue();

            Set<UUID> claimedIds = claimed.stream().map(ClaimedOutboxEvent::eventId).collect(Collectors.toSet());
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT event_id, status, owner, claim_token, attempt_count, lease_until FROM search_outbox ORDER BY id");
            assertThat(rows).hasSize(2);
            for (Map<String, Object> row : rows) {
                UUID eventId = UUID.fromString((String) row.get("event_id"));
                if (claimedIds.contains(eventId)) {
                    assertThat(row.get("status")).isEqualTo("PROCESSING");
                    assertThat(row.get("owner")).isEqualTo("node-a");
                    assertThat(row.get("claim_token")).isNotNull();
                    assertThat(row.get("attempt_count")).isEqualTo(1);
                    assertThat(row.get("lease_until")).isNotNull();
                } else {
                    assertThat(row.get("status")).isEqualTo("NEW");
                    assertThat(row.get("owner")).isNull();
                    assertThat(row.get("claim_token")).isNull();
                    assertThat(row.get("attempt_count")).isEqualTo(0);
                    assertThat(row.get("lease_until")).isNull();
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ProductDetails details(String name) {
        return new ProductDetails(name, null, "教材", "BOOK", "图书", new BigDecimal("10.00"), ProductStatus.ON_SALE);
    }
}
