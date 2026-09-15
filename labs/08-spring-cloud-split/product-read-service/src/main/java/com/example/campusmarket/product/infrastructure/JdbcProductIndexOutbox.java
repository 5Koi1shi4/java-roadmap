package com.example.campusmarket.product.infrastructure;

import com.example.campusmarket.product.event.ProductSnapshotEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.UUID;

/** 只有投影版本真正前进才生成可恢复的索引待办。 */
@Repository
public class JdbcProductIndexOutbox {
    private final JdbcTemplate jdbc;

    public JdbcProductIndexOutbox(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
    }

    public void enqueue(ProductSnapshotEvent event) {
        Objects.requireNonNull(event, "商品事件不能为空");
        jdbc.update("""
            INSERT INTO product_index_outbox(id,listing_id,aggregate_version,status)
            VALUES (?,?,?,'NEW')
            """, UUID.randomUUID().toString(), event.listingId().toString(),
            event.aggregateVersion());
    }
}
