package com.example.search.integration;

import com.example.search.application.product.CreateProductCommand;
import com.example.search.application.product.ProductCommandService;
import com.example.search.application.product.ProductView;
import com.example.search.application.product.UpdateProductCommand;
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
            Future<?> claim = executor.submit(() -> transactionTemplate.execute(status -> {
                await(start);
                outbox.claim("node-a", 50);
                return null;
            }));

            productWrite.get(5, TimeUnit.SECONDS);
            pauseUpdate.get(5, TimeUnit.SECONDS);
            claim.get(5, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT version FROM product WHERE id=?", Long.class, product.id()))
                    .isEqualTo(2L);
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
