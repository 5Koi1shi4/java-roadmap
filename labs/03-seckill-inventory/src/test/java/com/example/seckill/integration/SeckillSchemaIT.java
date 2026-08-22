package com.example.seckill.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;

@Testcontainers
@SpringBootTest
class SeckillSchemaIT {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUsername("root")
            .withPassword("test");

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    com.example.seckill.infrastructure.persistence.JdbcSeckillRepository repository;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Test
    void createsOnePurchaseUniqueIndexAndSeedProduct() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seckill_product WHERE id = 1", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics " +
                "WHERE table_schema = DATABASE() AND table_name = 'seckill_order' " +
                        "AND index_name = 'uk_seckill_order_user_product'", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock FROM seckill_product WHERE id = 1", Integer.class)).isEqualTo(10);
    }

    @Test
    void createsIdempotencyRecordWithUniqueKeyAndResponseFields() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE table_schema = DATABASE() AND table_name = 'idempotency_record'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics " +
                        "WHERE table_schema = DATABASE() AND table_name = 'idempotency_record' " +
                        "AND index_name = 'uk_idempotency_record_key'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                        "WHERE table_schema = DATABASE() AND table_name = 'idempotency_record' " +
                        "AND column_name IN ('idempotency_key', 'request_hash', 'status', 'response_status', " +
                        "'response_body', 'created_at', 'updated_at')", Integer.class)).isEqualTo(7);
    }
    @Test
    void readsIdempotencyRecordWithRowLockInsideReadCommittedTransaction() {
        jdbcTemplate.update("DELETE FROM idempotency_record");
        jdbcTemplate.update("INSERT INTO idempotency_record (idempotency_key, request_hash) VALUES (?, ?)",
                "lock-key", "hash");

        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    var record = repository.findByKeyForUpdate("lock-key");
                    assertThat(record).isPresent();
                });
    }

    @Test
    void lockingReadBlocksAnotherTransactionUntilTheLockingTransactionCommits() throws Exception {
        jdbcTemplate.update("DELETE FROM idempotency_record");
        jdbcTemplate.update("INSERT INTO idempotency_record (idempotency_key, request_hash) VALUES (?, ?)",
                "transaction-key", "hash");

        var firstTransaction = new DefaultTransactionDefinition();
        firstTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        org.springframework.transaction.TransactionStatus transaction =
                transactionManager.getTransaction(firstTransaction);
        Thread updater = null;
        try {
            assertThat(repository.findByKeyForUpdate("transaction-key")).isPresent();

            CountDownLatch updateFinished = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            updater = new Thread(() -> {
                try {
                    jdbcTemplate.update("UPDATE idempotency_record SET request_hash = ? " +
                            "WHERE idempotency_key = ?", "updated-hash", "transaction-key");
                } catch (Throwable exception) {
                    failure.set(exception);
                } finally {
                    updateFinished.countDown();
                }
            });
            updater.start();

            assertThat(awaitRowLockWait(5, TimeUnit.SECONDS)).isTrue();
            assertThat(updateFinished.await(300, TimeUnit.MILLISECONDS)).isFalse();
            transactionManager.commit(transaction);
            transaction = null;
            assertThat(updateFinished.await(5, TimeUnit.SECONDS)).isTrue();
            updater.join(5_000);
            assertThat(failure.get()).isNull();
            assertThat(jdbcTemplate.queryForObject("SELECT request_hash FROM idempotency_record " +
                    "WHERE idempotency_key = ?", String.class, "transaction-key"))
                    .isEqualTo("updated-hash");
        } catch (Throwable exception) {
            if (transaction != null) {
                transactionManager.rollback(transaction);
                transaction = null;
            }
            throw exception;
        } finally {
            if (transaction != null) {
                transactionManager.rollback(transaction);
            }
            if (updater != null) {
                updater.join(5_000);
                assertThat(updater.isAlive()).as("updater thread must always terminate").isFalse();
            }
        }
    }

    private boolean awaitRowLockWait(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            Integer waits = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.processlist " +
                            "WHERE command = 'Query' AND info LIKE 'UPDATE idempotency_record%'", Integer.class);
            if (waits != null && waits > 0) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    @Test
    void takesOverOnlyExpiredProcessingRecordAndReportsAffectedRows() {
        jdbcTemplate.update("DELETE FROM idempotency_record");
        jdbcTemplate.update("INSERT INTO idempotency_record (idempotency_key, request_hash, status, updated_at) " +
                        "VALUES (?, ?, 'PROCESSING', ?)", "expired-key", "hash",
                java.sql.Timestamp.from(Instant.now().minusSeconds(120)));
        jdbcTemplate.update("INSERT INTO idempotency_record (idempotency_key, request_hash, status, updated_at) " +
                        "VALUES (?, ?, 'PROCESSING', ?)", "fresh-key", "hash",
                java.sql.Timestamp.from(Instant.now()));

        int expiredRows = repository.takeOverProcessingIfExpired("expired-key", Instant.now().minusSeconds(60));
        int freshRows = repository.takeOverProcessingIfExpired("fresh-key", Instant.now().minusSeconds(60));

        assertThat(expiredRows).isEqualTo(1);
        assertThat(freshRows).isZero();
    }
}
