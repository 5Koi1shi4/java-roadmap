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
import java.sql.Types;
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
                        + "WHERE status = 'NEW' OR (status = 'PUBLISHING' AND lease_until <= ?) "
                        + "ORDER BY created_at, id LIMIT ?",
                (rs, rowNum) -> rs.getLong("id"), Timestamp.from(now), limit);
        List<com.example.order.infrastructure.mq.OutboxEvent> claimed = new ArrayList<>();
        for (Long id : candidates) {
            UUID claimToken = UUID.randomUUID();
            int updated = jdbcTemplate.update(
                    "UPDATE outbox_event SET status = 'PUBLISHING', lease_until = ?, claim_token = ?, "
                            + "publish_attempts = publish_attempts + 1 "
                            + "WHERE id = ? AND (status = 'NEW' OR (status = 'PUBLISHING' AND lease_until <= ?))",
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
                        + "WHERE event_id = ? AND (status = 'NEW' OR (status = 'PUBLISHING' AND lease_until <= ?))",
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
        if ("FAILED".equals(existing.status())) {
            return ConsumptionClaim.failed();
        }
        if (!"PROCESSING".equals(existing.status())
                || existing.leaseUntil() == null
                || existing.leaseUntil().isAfter(now)) {
            return ConsumptionClaim.inProgress();
        }

        UUID takeoverToken = UUID.randomUUID();
        int takenOver = jdbcTemplate.update(
                "UPDATE consumed_message SET status = 'PROCESSING', lease_until = ?, claim_token = ?, "
                        + "failure_category = NULL, last_error = NULL, completed_at = NULL "
                        + "WHERE event_id = ? AND status = 'PROCESSING' AND lease_until <= ?",
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
                        + "ON DUPLICATE KEY UPDATE status = IF(status = 'COMPLETED', 'COMPLETED', 'FAILED'), "
                        + "lease_until = IF(status = 'COMPLETED', lease_until, NULL), "
                        + "claim_token = IF(status = 'COMPLETED', claim_token, NULL), "
                        + "failure_category = IF(status = 'COMPLETED', failure_category, VALUES(failure_category)), "
                        + "last_error = IF(status = 'COMPLETED', last_error, VALUES(last_error)), "
                        + "completed_at = IF(status = 'COMPLETED', completed_at, VALUES(completed_at))",
                eventId.toString(), category, message, Timestamp.from(failedAt));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long insertManualFailure(UUID eventId, String payload, String category,
                                    String message, int retryCount, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO manual_failure "
                            + "(event_id, payload, failure_category, last_error, retry_count, "
                            + "manual_delivery_status, attempts, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            if (eventId == null) {
                statement.setNull(1, Types.CHAR);
            } else {
                statement.setString(1, eventId.toString());
            }
            statement.setString(2, payload);
            statement.setString(3, category);
            statement.setString(4, message);
            statement.setInt(5, retryCount);
            statement.setTimestamp(6, Timestamp.from(createdAt));
            statement.setTimestamp(7, Timestamp.from(createdAt));
            return statement;
        }, keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("No manual failure id returned");
        }
        return id.longValue();
    }

    /** Atomically claims pending or expired publishing rows and increments attempts. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ManualFailure> claimManualFailures(Instant now, Instant leaseUntil, int limit) {
        int bounded = Math.min(Math.max(limit, 0), 50);
        if (bounded == 0) {
            return List.of();
        }
        // A crashed owner must not leave an already exhausted row in PUBLISHING forever.
        jdbcTemplate.update(
                "UPDATE manual_failure SET manual_delivery_status = 'GIVE_UP', "
                        + "claim_token = NULL, lease_until = NULL, updated_at = ? "
                        + "WHERE manual_delivery_status = 'PUBLISHING' AND attempts >= 3 AND lease_until <= ?",
                Timestamp.from(now), Timestamp.from(now));
        List<Long> candidates = jdbcTemplate.query(
                "SELECT id FROM manual_failure "
                        + "WHERE attempts < 3 AND (manual_delivery_status = 'PENDING' "
                        + "OR (manual_delivery_status = 'PUBLISHING' AND lease_until <= ?)) "
                        + "ORDER BY id LIMIT ?",
                (rs, rowNum) -> rs.getLong("id"), Timestamp.from(now), bounded);
        List<ManualFailure> claimed = new ArrayList<>();
        for (Long id : candidates) {
            UUID claimToken = UUID.randomUUID();
            int updated = jdbcTemplate.update(
                    "UPDATE manual_failure SET manual_delivery_status = 'PUBLISHING', "
                            + "claim_token = ?, lease_until = ?, attempts = attempts + 1, updated_at = ? "
                            + "WHERE id = ? AND attempts < 3 AND (manual_delivery_status = 'PENDING' "
                            + "OR (manual_delivery_status = 'PUBLISHING' AND lease_until <= ?))",
                    claimToken.toString(), Timestamp.from(leaseUntil), Timestamp.from(now), id,
                    Timestamp.from(now));
            if (updated == 1) {
                ManualFailure failure = manualFailure(id, claimToken);
                if (failure != null) {
                    claimed.add(failure);
                }
            }
        }
        return claimed;
    }

    /** Fenced success transition; a late owner cannot update a reclaimed row. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markManualDelivered(long id, UUID claimToken, Instant now) {
        return jdbcTemplate.update(
                "UPDATE manual_failure SET manual_delivery_status = 'DELIVERED', "
                        + "lease_until = NULL, claim_token = NULL, updated_at = ? "
                        + "WHERE id = ? AND manual_delivery_status = 'PUBLISHING' AND claim_token = ?",
                Timestamp.from(now), id, claimToken.toString());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markManualDelivered(UUID eventId, UUID claimToken, Instant now) {
        return jdbcTemplate.update(
                "UPDATE manual_failure SET manual_delivery_status = 'DELIVERED', "
                        + "lease_until = NULL, claim_token = NULL, updated_at = ? "
                        + "WHERE event_id = ? AND manual_delivery_status = 'PUBLISHING' AND claim_token = ?",
                Timestamp.from(now), eventId.toString(), claimToken.toString());
    }

    /** Fenced failure transition; the third claimed attempt is terminal. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markManualPublishFailure(long id, UUID claimToken, Instant now) {
        return jdbcTemplate.update(
                "UPDATE manual_failure SET manual_delivery_status = "
                        + "IF(attempts >= 3, 'GIVE_UP', 'PENDING'), lease_until = NULL, claim_token = NULL, updated_at = ? "
                        + "WHERE id = ? AND manual_delivery_status = 'PUBLISHING' AND claim_token = ?",
                Timestamp.from(now), id, claimToken.toString());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markManualPublishFailure(UUID eventId, UUID claimToken, Instant now) {
        return jdbcTemplate.update(
                "UPDATE manual_failure SET manual_delivery_status = "
                        + "IF(attempts >= 3, 'GIVE_UP', 'PENDING'), lease_until = NULL, claim_token = NULL, updated_at = ? "
                        + "WHERE event_id = ? AND manual_delivery_status = 'PUBLISHING' AND claim_token = ?",
                Timestamp.from(now), eventId.toString(), claimToken.toString());
    }

    /** Marks a directly confirmed fallback publish delivered while the row is still pending. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markManualDelivered(long id, Instant now) {
        return jdbcTemplate.update(
                "UPDATE manual_failure SET manual_delivery_status = 'DELIVERED', updated_at = ? "
                        + "WHERE id = ? AND manual_delivery_status = 'PENDING'",
                Timestamp.from(now), id);
    }

    private ManualFailure manualFailure(long id) {
        return jdbcTemplate.query(
                        "SELECT id, event_id, payload, failure_category, last_error, retry_count, attempts, "
                                + "manual_delivery_status, claim_token, lease_until, updated_at FROM manual_failure WHERE id = ?",
                        (rs, rowNum) -> manualFailure(rs), id)
                .stream().findFirst().orElse(null);
    }

    private ManualFailure manualFailure(long id, UUID claimToken) {
        return jdbcTemplate.query(
                        "SELECT id, event_id, payload, failure_category, last_error, retry_count, attempts, "
                                + "manual_delivery_status, claim_token, lease_until, updated_at "
                                + "FROM manual_failure WHERE id = ? AND claim_token = ?",
                        (rs, rowNum) -> manualFailure(rs), id, claimToken.toString())
                .stream().findFirst().orElse(null);
    }

    private ManualFailure manualFailure(java.sql.ResultSet rs) throws java.sql.SQLException {
        String eventId = rs.getString("event_id");
        String claimToken = rs.getString("claim_token");
        Timestamp lease = rs.getTimestamp("lease_until");
        return new ManualFailure(
                rs.getLong("id"), eventId == null ? null : UUID.fromString(eventId),
                rs.getString("payload"), rs.getString("failure_category"), rs.getString("last_error"),
                rs.getInt("retry_count"), rs.getInt("attempts"),
                ManualDeliveryStatus.valueOf(rs.getString("manual_delivery_status")),
                claimToken == null ? null : UUID.fromString(claimToken),
                lease == null ? null : lease.toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    public enum ManualDeliveryStatus { PENDING, PUBLISHING, DELIVERED, GIVE_UP }

    public record ManualFailure(long id, UUID eventId, String payload, String category,
                                String message, int retryCount, int attempts,
                                ManualDeliveryStatus status, UUID claimToken,
                                Instant leaseUntil, Instant updatedAt) {
        /** Compatibility constructor for callers that create an unclaimed pending row. */
        public ManualFailure(long id, UUID eventId, String payload, String category,
                             String message, int retryCount, int attempts,
                             ManualDeliveryStatus status, Instant updatedAt) {
            this(id, eventId, payload, category, message, retryCount, attempts,
                    status, null, null, updatedAt);
        }
    }

    public record ConsumptionClaim(State state, UUID claimToken) {
        public enum State { PROCESSING, COMPLETED, FAILED, IN_PROGRESS }

        static ConsumptionClaim processing(UUID token) {
            return new ConsumptionClaim(State.PROCESSING, token);
        }

        static ConsumptionClaim completed() {
            return new ConsumptionClaim(State.COMPLETED, null);
        }

        static ConsumptionClaim failed() {
            return new ConsumptionClaim(State.FAILED, null);
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

        public boolean isTerminal() {
            return state == State.COMPLETED || state == State.FAILED;
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
