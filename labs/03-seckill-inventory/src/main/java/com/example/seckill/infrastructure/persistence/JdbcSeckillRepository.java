package com.example.seckill.infrastructure.persistence;

import com.example.seckill.domain.SeckillOrder;
import com.example.seckill.domain.SeckillProduct;
import com.example.seckill.domain.SeckillRepository;
import com.example.seckill.domain.IdempotencyRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;

@Repository
public class JdbcSeckillRepository implements SeckillRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate;

    public JdbcSeckillRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int decrementStockIfAvailable(long productId) {
        return jdbcTemplate.update(
                "UPDATE seckill_product SET stock = stock - 1 WHERE id = ? AND stock > 0",
                productId);
    }

    @Override
    public void restoreStock(long productId) {
        jdbcTemplate.update("UPDATE seckill_product SET stock = stock + 1 WHERE id = ?", productId);
    }

    @Override
    public Optional<SeckillProduct> findProduct(long productId) {
        return jdbcTemplate.query("SELECT id, name, stock FROM seckill_product WHERE id = ?",
                        (rs, rowNum) -> new SeckillProduct(rs.getLong("id"), rs.getString("name"), rs.getInt("stock")),
                        productId)
                .stream().findFirst();
    }

    @Override
    public SeckillOrder insertOrder(long userId, long productId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO seckill_order (user_id, product_id) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setLong(2, productId);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("No generated order id returned");
        }
        return new SeckillOrder(key.longValue(), userId, productId, Instant.now());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> findByKey(String key) {
        return jdbcTemplate.query(
                        "SELECT idempotency_key, request_hash, status, response_status, response_body, " +
                                "created_at, updated_at FROM idempotency_record WHERE idempotency_key = ?",
                        (rs, rowNum) -> new IdempotencyRecord(
                                rs.getString("idempotency_key"),
                                rs.getString("request_hash"),
                                rs.getString("status"),
                                (Integer) rs.getObject("response_status"),
                                normalizeJson(rs.getString("response_body")),
                                rs.getTimestamp("created_at").toInstant(),
                                rs.getTimestamp("updated_at").toInstant()),
                        key)
                .stream().findFirst();
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<IdempotencyRecord> findByKeyForUpdate(String key) {
        return jdbcTemplate.query(
                        "SELECT idempotency_key, request_hash, status, response_status, response_body, " +
                                "created_at, updated_at FROM idempotency_record WHERE idempotency_key = ? FOR UPDATE",
                        (rs, rowNum) -> new IdempotencyRecord(
                                rs.getString("idempotency_key"),
                                rs.getString("request_hash"),
                                rs.getString("status"),
                                (Integer) rs.getObject("response_status"),
                                normalizeJson(rs.getString("response_body")),
                                rs.getTimestamp("created_at").toInstant(),
                                rs.getTimestamp("updated_at").toInstant()),
                        key)
                .stream().findFirst();
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int takeOverProcessingIfExpired(String key, Instant expiredBefore) {
        return jdbcTemplate.update(
                "UPDATE idempotency_record SET updated_at = CURRENT_TIMESTAMP " +
                        "WHERE idempotency_key = ? AND status = 'PROCESSING' AND updated_at < ?",
                key, java.sql.Timestamp.from(expiredBefore));
    }

    private static String normalizeJson(String body) {
        if (body == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(canonical(JSON.readTree(body)));
        } catch (Exception ignored) {
            return body;
        }
    }

    private static JsonNode canonical(JsonNode node) {
        if (!node.isObject()) {
            return node;
        }
        ObjectNode sorted = JSON.createObjectNode();
        java.util.List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        names.sort(String::compareTo);
        names.forEach(name -> sorted.set(name, canonical(node.get(name))));
        return sorted;
    }

    @Override
    public int insertProcessing(String key, String requestHash) {
        return jdbcTemplate.update(
                "INSERT IGNORE INTO idempotency_record (idempotency_key, request_hash, status) VALUES (?, ?, 'PROCESSING')",
                key, requestHash);
    }

    @Override
    public void saveResponse(String key, int status, String body) {
        jdbcTemplate.update(
                "UPDATE idempotency_record SET status = 'SUCCEEDED', response_status = ?, response_body = ? " +
                        "WHERE idempotency_key = ?",
                status, body, key);
    }
}
