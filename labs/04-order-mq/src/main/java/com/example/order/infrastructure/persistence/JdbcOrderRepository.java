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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
            UUID claimToken = UUID.randomUUID();
            int updated = jdbcTemplate.update(
                    "UPDATE outbox_event SET status = 'PUBLISHING', lease_until = ?, claim_token = ?, "
                            + "publish_attempts = publish_attempts + 1 "
                            + "WHERE id = ? AND (status = 'NEW' OR (status = 'PUBLISHING' AND lease_until < ?))",
                    Timestamp.from(leaseUntil), claimToken.toString(), id, Timestamp.from(now));
            if (updated == 1) {
                claimed.add(findOutboxEvent(id));
            }
        }
        return claimed;
    }

    public boolean claim(UUID eventId, Instant now, Instant leaseUntil) {
        UUID claimToken = UUID.randomUUID();
        return jdbcTemplate.update(
                "UPDATE outbox_event SET status = 'PUBLISHING', lease_until = ?, claim_token = ?, "
                        + "publish_attempts = publish_attempts + 1 "
                        + "WHERE event_id = ? AND (status = 'NEW' OR (status = 'PUBLISHING' AND lease_until < ?))",
                Timestamp.from(leaseUntil), claimToken.toString(), eventId.toString(), Timestamp.from(now)) == 1;
    }

    public UUID claimToken(UUID eventId) {
        String token = jdbcTemplate.query("SELECT claim_token FROM outbox_event WHERE event_id = ?",
                        (rs, rowNum) -> rs.getString("claim_token"), eventId.toString())
                .stream().findFirst().orElse(null);
        return token == null ? null : UUID.fromString(token);
    }

    public int markPublished(UUID eventId, UUID claimToken, Instant publishedAt) {
        return jdbcTemplate.update(
                "UPDATE outbox_event SET status = 'PUBLISHED', published_at = ?, "
                        + "lease_until = NULL, claim_token = NULL, last_error = NULL "
                        + "WHERE event_id = ? AND status = 'PUBLISHING' AND claim_token = ?",
                Timestamp.from(publishedAt), eventId.toString(), claimToken.toString());
    }

    public int releaseForRetry(UUID eventId, UUID claimToken, String category, String message) {
        String error = "[" + category + "] " + message;
        return jdbcTemplate.update(
                "UPDATE outbox_event SET status = 'NEW', lease_until = NULL, claim_token = NULL, last_error = ? "
                        + "WHERE event_id = ? AND status = 'PUBLISHING' AND claim_token = ?",
                error, eventId.toString(), claimToken.toString());
    }

    /** Claims a consumed event, fencing a previous owner when its lease has expired. */
    public ConsumptionClaim beginConsumption(UUID eventId, Instant now, Instant leaseUntil) {
        UUID token = UUID.randomUUID();
        try {
            int inserted = jdbcTemplate.update(
                    "INSERT INTO consumed_message (event_id, status, lease_until, claim_token) "
                            + "VALUES (?, 'PROCESSING', ?, ?)",
                    eventId.toString(), Timestamp.from(leaseUntil), token.toString());
            if (inserted == 1) {
                return ConsumptionClaim.processing(token);
            }
        } catch (DuplicateKeyException ignored) {
            // A concurrent consumer already owns the unique event id; inspect its state below.
        }

        ConsumptionRow existing = jdbcTemplate.query(
                        "SELECT status, lease_until, claim_token FROM consumed_message WHERE event_id = ?",
                        (rs, rowNum) -> new ConsumptionRow(
                                rs.getString("status"),
                                rs.getTimestamp("lease_until") == null
                                        ? null : rs.getTimestamp("lease_until").toInstant(),
                                rs.getString("claim_token")),
                        eventId.toString())
                .stream().findFirst().orElse(null);
        if (existing == null) {
            return beginConsumption(eventId, now, leaseUntil);
        }
        if ("COMPLETED".equals(existing.status())) {
            return ConsumptionClaim.completed();
        }
        if (!"PROCESSING".equals(existing.status())
                || existing.leaseUntil() == null
                || !existing.leaseUntil().isBefore(now)) {
            return ConsumptionClaim.inProgress();
        }

        UUID takeoverToken = UUID.randomUUID();
        int takenOver = jdbcTemplate.update(
                "UPDATE consumed_message SET status = 'PROCESSING', lease_until = ?, claim_token = ?, "
                        + "failure_category = NULL, last_error = NULL, completed_at = NULL "
                        + "WHERE event_id = ? AND status = 'PROCESSING' AND lease_until < ?",
                Timestamp.from(leaseUntil), takeoverToken.toString(), eventId.toString(), Timestamp.from(now));
        return takenOver == 1 ? ConsumptionClaim.processing(takeoverToken) : ConsumptionClaim.inProgress();
    }

    /** Marks a message complete only when this consumer still owns its fencing token. */
    public int completeConsumption(UUID eventId, UUID claimToken, Instant completedAt) {
        return jdbcTemplate.update(
                "UPDATE consumed_message SET status = 'COMPLETED', lease_until = NULL, completed_at = ?, "
                        + "failure_category = NULL, last_error = NULL WHERE event_id = ? "
                        + "AND status = 'PROCESSING' AND claim_token = ?",
                Timestamp.from(completedAt), eventId.toString(), claimToken.toString());
    }

    /** Persists the terminal failure independently of the rolled-back listener transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordConsumptionFailure(UUID eventId, String category, String message, Instant failedAt) {
        return jdbcTemplate.update(
                "INSERT INTO consumed_message "
                        + "(event_id, status, failure_category, last_error, completed_at) "
                        + "VALUES (?, 'FAILED', ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE status = 'FAILED', lease_until = NULL, claim_token = NULL, "
                        + "failure_category = VALUES(failure_category), last_error = VALUES(last_error), "
                        + "completed_at = VALUES(completed_at)",
                eventId.toString(), category, message, Timestamp.from(failedAt));
    }

    public record ConsumptionClaim(State state, UUID claimToken) {
        public enum State { PROCESSING, COMPLETED, IN_PROGRESS }

        static ConsumptionClaim processing(UUID token) {
            return new ConsumptionClaim(State.PROCESSING, token);
        }

        static ConsumptionClaim completed() {
            return new ConsumptionClaim(State.COMPLETED, null);
        }

        static ConsumptionClaim inProgress() {
            return new ConsumptionClaim(State.IN_PROGRESS, null);
        }

        public boolean isProcessing() {
            return state == State.PROCESSING;
        }

        public boolean isCompleted() {
            return state == State.COMPLETED;
        }
    }

    private record ConsumptionRow(String status, Instant leaseUntil, String claimToken) {
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
                "SELECT event_id, claim_token, aggregate_type, aggregate_id, event_type, payload, created_at "
                        + "FROM outbox_event WHERE id = ?",
                (rs, rowNum) -> new com.example.order.infrastructure.mq.OutboxEvent(
                        UUID.fromString(rs.getString("event_id")),
                        UUID.fromString(rs.getString("claim_token")),
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
