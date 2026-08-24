package com.example.search.infrastructure.persistence;

import com.example.search.application.sync.OutboxEventType;
import com.example.search.application.sync.SearchOutboxRepository;
import com.example.search.domain.ProductSearchSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class JdbcSearchOutboxRepository implements SearchOutboxRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcSearchOutboxRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(ProductSearchSnapshot snapshot, OutboxEventType eventType) {
        final String payload;
        try {
            payload = objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize search snapshot", e);
        }
        Instant createdAt = snapshot.updatedAt();
        jdbc.update("INSERT INTO search_outbox(event_id, product_id, product_version, event_type, payload, status, available_at, created_at) VALUES (?, ?, ?, ?, ?, 'NEW', ?, ?)",
                UUID.randomUUID().toString(), snapshot.productId(), snapshot.sourceVersion(), eventType.name(), payload,
                Timestamp.from(createdAt), Timestamp.from(createdAt));
    }
}
