package com.example.campusmarket.shared.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.UUID;

/** 市场库自有的持久卖家互斥锁，供所有改变余额事实的事务共用。 */
@Repository
@Profile("!test")
public class SellerBalanceLockRepository {
    private final JdbcTemplate jdbc;

    public SellerBalanceLockRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
    }

    public void lock(UUID sellerId) {
        Objects.requireNonNull(sellerId, "卖家ID不能为空");
        String id = sellerId.toString();
        jdbc.update("INSERT INTO seller_balance_lock(seller_id) VALUES (?) "
            + "ON DUPLICATE KEY UPDATE seller_id=seller_id", id);
        jdbc.query("SELECT seller_id FROM seller_balance_lock WHERE seller_id=? FOR UPDATE",
            (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> {
                if (!rs.next()) throw new IllegalStateException("卖家锁行不存在");
                return null;
            }, id);
    }
}
