package com.example.seckill.infrastructure.persistence;

import com.example.seckill.domain.SeckillOrder;
import com.example.seckill.domain.SeckillProduct;
import com.example.seckill.domain.SeckillRepository;
import com.example.seckill.domain.IdempotencyRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;

@Repository
public class JdbcSeckillRepository implements SeckillRepository {
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
    public Optional<IdempotencyRecord> findByKey(String key) {
        return jdbcTemplate.query(
                        "SELECT idempotency_key, request_hash, status, response_status, response_body, " +
                                "created_at, updated_at FROM idempotency_record WHERE idempotency_key = ?",
                        (rs, rowNum) -> new IdempotencyRecord(
                                rs.getString("idempotency_key"),
                                rs.getString("request_hash"),
                                rs.getString("status"),
                                (Integer) rs.getObject("response_status"),
                                rs.getString("response_body"),
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
                                rs.getString("response_body"),
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

    @Override
    public int insertProcessing(String key, String requestHash) {
        return jdbcTemplate.update(
                "INSERT INTO idempotency_record (idempotency_key, request_hash, status) VALUES (?, ?, 'PROCESSING') " +
                        "ON DUPLICATE KEY UPDATE idempotency_key = idempotency_key",
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
