package com.example.campusmarket.product.infrastructure;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.UUID;

/** eventId 唯一；与投影及索引待办在同一事务完成。 */
@Repository
public class JdbcProductInbox {
    private final JdbcTemplate jdbc;

    public JdbcProductInbox(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
    }

    public boolean complete(UUID eventId) {
        Objects.requireNonNull(eventId, "事件ID不能为空");
        try {
            jdbc.update("""
                INSERT INTO product_inbox(event_id,completed_at)
                VALUES (?,CURRENT_TIMESTAMP(6))
                """, eventId.toString());
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }
}
