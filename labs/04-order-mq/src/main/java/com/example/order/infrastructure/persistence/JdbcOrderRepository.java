package com.example.order.infrastructure.persistence;

import com.example.order.application.OrderTimeoutEvent;
import com.example.order.application.StoredOutbox;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.List;

@Repository
public class JdbcOrderRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcOrderRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Autowired
    public JdbcOrderRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public int decrementStockIfAvailable(long productId, int quantity) {
        return jdbcTemplate.update(
                "UPDATE order_stock SET available = available - ? "
                        + "WHERE product_id = ? AND available >= ?",
                quantity, productId, quantity);
    }

    public int decrementStockIfAvailable(long productId) {
        return decrementStockIfAvailable(productId, 1);
    }

    public long insertPendingOrder(long productId, int quantity) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO orders (product_id, quantity, status, created_at, updated_at) "
                            + "VALUES (?, ?, 'PENDING_PAYMENT', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, productId);
            statement.setInt(2, quantity);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("No generated order id returned");
        }
        return key.longValue();
    }

    public void insertTimeoutOutbox(OrderTimeoutEvent event) {
        jdbcTemplate.update(
                "INSERT INTO outbox_event "
                        + "(event_id, aggregate_type, aggregate_id, event_type, payload, status, created_at) "
                        + "VALUES (?, 'ORDER', ?, ?, ?, 'NEW', UTC_TIMESTAMP(6))",
                event.eventId().toString(), event.orderId(), event.eventType(), serialize(event));
    }

    public int markPaidIfPending(long orderId) {
        return jdbcTemplate.update(
                "UPDATE orders SET status = 'PAID', updated_at = UTC_TIMESTAMP(6) "
                        + "WHERE id = ? AND status = 'PENDING_PAYMENT'",
                orderId);
    }

    public int cancelIfPending(long orderId) {
        return jdbcTemplate.update(
                "UPDATE orders SET status = 'CANCELLED', updated_at = UTC_TIMESTAMP(6) "
                        + "WHERE id = ? AND status = 'PENDING_PAYMENT'",
                orderId);
    }

    public void releaseStock(long productId, int quantity) {
        jdbcTemplate.update(
                "UPDATE order_stock SET available = available + ? WHERE product_id = ?",
                quantity, productId);
    }

    public String orderStatus(long orderId) {
        return jdbcTemplate.query("SELECT status FROM orders WHERE id = ?",
                        (rs, rowNum) -> rs.getString("status"), orderId)
                .stream().findFirst().orElse(null);
    }

    public String status(long orderId) {
        return orderStatus(orderId);
    }

    public OrderProductQuantity orderProductAndQuantity(long orderId) {
        return jdbcTemplate.query("SELECT product_id, quantity FROM orders WHERE id = ?",
                        (rs, rowNum) -> new OrderProductQuantity(rs.getLong("product_id"), rs.getInt("quantity")), orderId)
                .stream().findFirst().orElse(null);
    }

    public List<StoredOutbox> outboxForOrder(long orderId) {
        return jdbcTemplate.query(
                "SELECT event_type, status, JSON_UNQUOTE(JSON_EXTRACT(payload, '$.schemaVersion')) AS schemaVersion "
                        + "FROM outbox_event WHERE aggregate_id = ? ORDER BY id",
                (rs, rowNum) -> new StoredOutbox(rs.getString("event_type"), rs.getString("status"), rs.getInt("schemaVersion")),
                orderId);
    }

    /** Atomically claims up to {@code batchSize} NEW or expired PUBLISHING rows. */
    public List<com.example.order.infrastructure.mq.OutboxEvent> claimPublishable(
            Instant now, Instant leaseUntil, int batchSize) {
        int limit = Math.min(Math.max(batchSize, 0), 50);
        if (limit == 0) {
            return List.of();
        }
        List<Long> candidates = jdbcTemplate.query(
                "SELECT id FROM outbox_event "
                        + "WHERE status = 'NEW' OR (status = 'PUBLISHING' AND lease_until < ?) "
                        + "ORDER BY created_at, id LIMIT ?",
                (rs, rowNum) -> rs.getLong("id"), Timestamp.from(now), limit);
        List<com.example.order.infrastructure.mq.OutboxEvent> claimed = new ArrayList<>();
        for (Long id : candidates) {
            int updated = jdbcTemplate.update(
                    "UPDATE outbox_event SET status = 'PUBLISHING', lease_until = ?, "
                            + "publish_attempts = publish_attempts + 1 "
                            + "WHERE id = ? AND (status = 'NEW' OR (status = 'PUBLISHING' AND lease_until < ?))",
                    Timestamp.from(leaseUntil), id, Timestamp.from(now));
            if (updated == 1) {
                claimed.add(findOutboxEvent(id));
            }
        }
        return claimed;
    }

    public boolean claim(UUID eventId, Instant now, Instant leaseUntil) {
        return jdbcTemplate.update(
                "UPDATE outbox_event SET status = 'PUBLISHING', lease_until = ?, "
                        + "publish_attempts = publish_attempts + 1 "
                        + "WHERE event_id = ? AND (status = 'NEW' OR (status = 'PUBLISHING' AND lease_until < ?))",
                Timestamp.from(leaseUntil), eventId.toString(), Timestamp.from(now)) == 1;
    }

    public int markPublished(UUID eventId, Instant publishedAt) {
        return jdbcTemplate.update(
                "UPDATE outbox_event SET status = 'PUBLISHED', published_at = ?, "
                        + "lease_until = NULL, last_error = NULL "
                        + "WHERE event_id = ? AND status = 'PUBLISHING'",
                Timestamp.from(publishedAt), eventId.toString());
    }

    public int releaseForRetry(UUID eventId, String category, String message) {
        String error = "[" + category + "] " + message;
        return jdbcTemplate.update(
                "UPDATE outbox_event SET status = 'NEW', lease_until = NULL, last_error = ? "
                        + "WHERE event_id = ? AND status = 'PUBLISHING'",
                error, eventId.toString());
    }

    /** Small database fixture helpers used by lease integration tests. */
    public void insertOutbox(UUID eventId, String status) {
        jdbcTemplate.update(
                "INSERT INTO outbox_event "
                        + "(event_id, aggregate_type, aggregate_id, event_type, payload, status, created_at) "
                        + "VALUES (?, 'ORDER', 1, 'ORDER_TIMEOUT', ?, ?, UTC_TIMESTAMP(6))",
                eventId.toString(), "{}", status);
    }

    public void forcePublishing(UUID eventId, Instant leaseUntil) {
        jdbcTemplate.update(
                "UPDATE outbox_event SET status = 'PUBLISHING', lease_until = ? WHERE event_id = ?",
                Timestamp.from(leaseUntil), eventId.toString());
    }

    private com.example.order.infrastructure.mq.OutboxEvent findOutboxEvent(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT event_id, aggregate_type, aggregate_id, event_type, payload, created_at "
                        + "FROM outbox_event WHERE id = ?",
                (rs, rowNum) -> new com.example.order.infrastructure.mq.OutboxEvent(
                        UUID.fromString(rs.getString("event_id")),
                        rs.getString("aggregate_type"),
                        rs.getLong("aggregate_id"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getTimestamp("created_at").toInstant()),
                id);
    }

    private String serialize(OrderTimeoutEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize timeout event", exception);
        }
    }

    public record OrderProductQuantity(long productId, int quantity) {
    }
}
