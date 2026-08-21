package com.example.seckill.infrastructure.persistence;

import com.example.seckill.domain.SeckillOrder;
import com.example.seckill.domain.SeckillProduct;
import com.example.seckill.domain.SeckillRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

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
}
