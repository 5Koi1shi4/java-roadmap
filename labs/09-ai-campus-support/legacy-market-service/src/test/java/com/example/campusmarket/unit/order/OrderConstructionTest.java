package com.example.campusmarket.unit.order;

import com.example.campusmarket.catalog.infrastructure.JdbcInventoryRepository;
import com.example.campusmarket.order.api.OrderController;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderConstructionTest {
    @Test
    void rejectsNullOrderControllerDependency() {
        assertThatThrownBy(() -> new OrderController(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullInventoryDependencies() {
        PlatformTransactionManager transactions = new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
            @Override public void commit(TransactionStatus status) { }
            @Override public void rollback(TransactionStatus status) { }
        };
        assertThatThrownBy(() -> new JdbcInventoryRepository(null, transactions, new com.example.campusmarket.catalog.search.SearchOutboxRepository(new JdbcTemplate(), new com.fasterxml.jackson.databind.ObjectMapper())))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JdbcInventoryRepository(new JdbcTemplate(), transactions, null))
            .isInstanceOf(NullPointerException.class);
    }
}
