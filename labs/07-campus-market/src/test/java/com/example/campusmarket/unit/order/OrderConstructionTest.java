package com.example.campusmarket.unit.order;

import com.example.campusmarket.catalog.infrastructure.JdbcInventoryRepository;
import com.example.campusmarket.order.api.OrderController;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderConstructionTest {
    @Test
    void rejectsNullOrderControllerDependency() {
        assertThatThrownBy(() -> new OrderController(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullInventoryDependencies() {
        assertThatThrownBy(() -> new JdbcInventoryRepository(null, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JdbcInventoryRepository(new JdbcTemplate(), null))
            .isInstanceOf(NullPointerException.class);
    }
}
