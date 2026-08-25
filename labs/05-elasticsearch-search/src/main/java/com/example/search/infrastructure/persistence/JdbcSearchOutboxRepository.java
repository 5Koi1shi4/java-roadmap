package com.example.search.infrastructure.persistence;

import com.example.search.application.sync.OutboxEventType;
import com.example.search.application.sync.ClaimedOutboxEvent;
import com.example.search.application.sync.SearchOutboxEvent;
import com.example.search.application.sync.SearchOutboxRepository;
import com.example.search.domain.ProductSearchSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
        jdbc.update("INSERT INTO search_outbox(event_id, product_id, product_version, event_type, payload, status, available_at, created_at) VALUES (?, ?, ?, ?, ?, 'NEW', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                UUID.randomUUID().toString(), snapshot.productId(), snapshot.sourceVersion(), eventType.name(), payload);
    }

    @Override
    public long highWatermark() {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM search_outbox", Long.class);
        return value == null ? 0L : value;
    }

    @Override
    public List<SearchOutboxEvent> eventsBetween(long exclusiveStart, long inclusiveEnd, int size) {
        if (exclusiveStart < 0 || inclusiveEnd < exclusiveStart) throw new IllegalArgumentException("invalid watermark range");
        if (size < 1 || size > 500) throw new IllegalArgumentException("size must be between 1 and 500");
        return jdbc.query("SELECT id, event_id, product_id, product_version, event_type, payload, attempt_count "
                        + "FROM search_outbox WHERE id > ? AND id <= ? ORDER BY id LIMIT ?", this::mapEvent,
                exclusiveStart, inclusiveEnd, size);
    }

    @Override
    @Transactional
    public List<ClaimedOutboxEvent> claim(String owner, int limit) {
        if (owner == null || owner.isBlank() || owner.length() > 128) {
            throw new IllegalArgumentException("owner must contain 1 to 128 characters");
        }
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("limit must be between 1 and 50");
        }

        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM search_outbox "
                        + "WHERE available_at <= UTC_TIMESTAMP(6) "
                        + "AND (status = 'NEW' "
                        + "OR (status = 'PROCESSING' AND lease_until < UTC_TIMESTAMP(6))) "
                        + "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
                Long.class, limit);

        List<ClaimedOutboxEvent> claimed = new ArrayList<>(ids.size());
        for (Long id : ids) {
            UUID token = UUID.randomUUID();
            int changed = jdbc.update(
                    "UPDATE search_outbox SET status='PROCESSING', owner=?, claim_token=?, "
                            + "lease_until=TIMESTAMPADD(SECOND, 30, UTC_TIMESTAMP(6)), "
                            + "attempt_count=attempt_count+1 WHERE id=?",
                    owner, token.toString(), id);
            if (changed != 1) {
                throw new IllegalStateException("outbox claim lost for id " + id);
            }
            SearchOutboxEvent event = findById(id);
            LocalDateTime lease = jdbc.queryForObject(
                    "SELECT lease_until FROM search_outbox WHERE id=?",
                    (rs, rowNum) -> rs.getObject("lease_until", LocalDateTime.class), id);
            claimed.add(new ClaimedOutboxEvent(event.id(), event.eventId(), event.productId(), event.productVersion(),
                    event.eventType(), event.snapshot(), event.attemptCount(), owner, token,
                    lease.toInstant(ZoneOffset.UTC)));
        }
        return claimed;
    }

    @Override
    public boolean complete(UUID eventId, UUID token) {
        return jdbc.update(
                "UPDATE search_outbox SET status='COMPLETED', owner=NULL, claim_token=NULL, lease_until=NULL, "
                        + "completed_at=UTC_TIMESTAMP(6), last_error=NULL "
                        + "WHERE event_id=? AND status='PROCESSING' AND claim_token=?",
                eventId.toString(), token.toString()) == 1;
    }

    @Override
    public boolean reschedule(UUID eventId, UUID token, Duration delay, String reason) {
        long micros = toMicros(delay);
        return jdbc.update(
                "UPDATE search_outbox SET status='NEW', owner=NULL, claim_token=NULL, lease_until=NULL, "
                        + "available_at=TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6)), last_error=? "
                        + "WHERE event_id=? AND status='PROCESSING' AND claim_token=?",
                micros, normalizeReason(reason), eventId.toString(), token.toString()) == 1;
    }

    @Override
    public boolean fail(UUID eventId, UUID token, String reason) {
        return jdbc.update(
                "UPDATE search_outbox SET status='FAILED', owner=NULL, claim_token=NULL, lease_until=NULL, "
                        + "last_error=? WHERE event_id=? AND status='PROCESSING' AND claim_token=?",
                normalizeReason(reason), eventId.toString(), token.toString()) == 1;
    }

    @Override
    public boolean hasUnexpiredProcessing() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM search_outbox "
                + "WHERE status='PROCESSING' AND lease_until > UTC_TIMESTAMP(6)", Integer.class);
        return count != null && count > 0;
    }

    private SearchOutboxEvent findById(long id) {
        return jdbc.queryForObject(
                "SELECT id, event_id, product_id, product_version, event_type, payload, attempt_count "
                        + "FROM search_outbox WHERE id=?",
                this::mapEvent, id);
    }

    private SearchOutboxEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        try {
            return new SearchOutboxEvent(rs.getLong("id"), UUID.fromString(rs.getString("event_id")),
                    rs.getLong("product_id"), rs.getLong("product_version"),
                    OutboxEventType.valueOf(rs.getString("event_type")),
                    objectMapper.readValue(rs.getString("payload"), ProductSearchSnapshot.class),
                    rs.getInt("attempt_count"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not deserialize search outbox payload", e);
        }
    }

    private static long toMicros(Duration delay) {
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        try {
            return Math.addExact(Math.multiplyExact(delay.getSeconds(), 1_000_000L), delay.getNano() / 1_000L);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("delay is too large", e);
        }
    }

    private static String normalizeReason(String reason) {
        if (reason == null) {
            return null;
        }
        String normalized = reason.replace("\r", "").replace("\n", "");
        return normalized.length() <= 1024 ? normalized : normalized.substring(0, 1024);
    }
}
