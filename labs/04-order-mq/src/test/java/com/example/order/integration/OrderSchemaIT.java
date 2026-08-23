package com.example.order.integration;

import com.example.order.infrastructure.mq.RabbitTopologyConfiguration;
import com.example.order.infrastructure.persistence.JdbcOrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = OrderSchemaIT.TestApplication.class, webEnvironment = WebEnvironment.NONE)
class OrderSchemaIT {
    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    RabbitAdmin rabbitAdmin;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        SharedContainers.registerProperties(registry);
    }

    @BeforeEach
    void isolate() {
        SharedContainers.cleanDatabase(jdbcTemplate);
        SharedContainers.purgeQueues(rabbitAdmin);
    }

    @Test
    void createsReliableMessagingTablesAndUniqueEventIndexes() {
        assertThat(countTable("orders")).isEqualTo(1);
        assertThat(countTable("order_stock")).isEqualTo(1);
        assertThat(countTable("outbox_event")).isEqualTo(1);
        assertThat(countTable("consumed_message")).isEqualTo(1);
        assertThat(countIndex("outbox_event", "uk_outbox_event_id")).isEqualTo(1);
        assertThat(countIndex("consumed_message", "uk_consumed_message_event_id")).isEqualTo(1);
    }

    @Test
    void purgeQueuesCompletesBeforeTheNextMessageIsPublished() {
        rabbitTemplate.convertAndSend("", RabbitTopologyConfiguration.MANUAL_QUEUE, "stale");

        SharedContainers.purgeQueues(rabbitAdmin);

        assertThat(rabbitTemplate.receive(RabbitTopologyConfiguration.MANUAL_QUEUE, 1_000L)).isNull();
    }

    private int countTable(String tableName) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class, tableName);
    }

    private int countIndex(String tableName, String indexName) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class, tableName, indexName);
    }

    @EnableAutoConfiguration
    @Import(RabbitTopologyConfiguration.class)
    static class TestApplication {
        @Bean
        JdbcOrderRepository jdbcOrderRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
            return new JdbcOrderRepository(jdbcTemplate, objectMapper);
        }
    }
}
