package com.example.campusmarket.product.infrastructure;

import com.example.campusmarket.product.event.ProductSnapshotEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Objects;

/** 只允许版本前进的读侧商品投影。 */
@Repository
public class JdbcProductProjection {
    private final JdbcTemplate jdbc;

    public JdbcProductProjection(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
    }

    public boolean apply(ProductSnapshotEvent event) {
        Objects.requireNonNull(event, "商品事件不能为空");
        if (updateIfNewer(event) == 1) return true;
        try {
            ProductSnapshotEvent.ProductSnapshot snapshot = event.snapshot();
            jdbc.update("""
                INSERT INTO product_projection(
                    listing_id,aggregate_version,title,description,category,unit_price_fen,
                    available_quantity,status,updated_at)
                VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6))
                """, event.listingId().toString(), event.aggregateVersion(), snapshot.title(),
                snapshot.description(), snapshot.category(), snapshot.unitPriceFen(),
                snapshot.availableQuantity(), snapshot.status());
            return true;
        } catch (DuplicateKeyException concurrentInsert) {
            // 并发首次投影：另一个事务可能插入较低版本，锁释放后再条件推进。
            return updateIfNewer(event) == 1;
        }
    }

    private int updateIfNewer(ProductSnapshotEvent event) {
        ProductSnapshotEvent.ProductSnapshot snapshot = event.snapshot();
        return jdbc.update("""
            UPDATE product_projection
            SET aggregate_version=?,title=?,description=?,category=?,unit_price_fen=?,
                available_quantity=?,status=?,updated_at=CURRENT_TIMESTAMP(6)
            WHERE listing_id=? AND aggregate_version < ?
            """, event.aggregateVersion(), snapshot.title(), snapshot.description(),
            snapshot.category(), snapshot.unitPriceFen(), snapshot.availableQuantity(),
            snapshot.status(), event.listingId().toString(), event.aggregateVersion());
    }
}
