package com.example.campusmarket.catalog.infrastructure;

import com.example.campusmarket.catalog.application.InventoryPort;
import com.example.campusmarket.catalog.search.SearchOutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.Objects;

@Repository
public class JdbcInventoryRepository implements InventoryPort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SearchOutboxRepository searchOutbox;

    @Autowired
    public JdbcInventoryRepository(JdbcTemplate jdbc,
                                   org.springframework.transaction.PlatformTransactionManager transactionManager,
                                   SearchOutboxRepository searchOutbox) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
        this.searchOutbox = Objects.requireNonNull(searchOutbox, "搜索 Outbox 不能为空");
    }

    @Override
    public boolean deduct(UUID listingId, int quantity, String businessKey) {
        return deduct(listingId, quantity, businessKey, null);
    }

    @Override
    public boolean deduct(UUID listingId, int quantity, String businessKey, UUID orderId) {
        return execute(() -> change(listingId, quantity, businessKey, "ORDER_DEDUCT", false, orderId));
    }

    @Override
    public boolean restore(UUID listingId, int quantity, String businessKey) {
        return execute(() -> change(listingId, quantity, businessKey, "ORDER_CANCEL_RESTORE", true, null));
    }

    @Override
    public boolean quarantine(UUID listingId, int quantity, String businessKey) {
        return execute(() -> change(listingId, quantity, businessKey, "RETURN_QUARANTINE", false, null));
    }

    private boolean execute(Supplier<Boolean> operation) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Boolean result = transactions.execute(status -> operation.get());
                return Boolean.TRUE.equals(result);
            } catch (PessimisticLockingFailureException e) {
                if (attempt == 1) throw e;
            }
        }
        throw new IllegalStateException("库存事务未执行");
    }

    private boolean change(UUID listingId, int quantity, String key, String reason, boolean restore, UUID orderId) {
        validate(quantity, key);
        var listingExists = jdbc.query("SELECT id FROM listing WHERE id = ? FOR UPDATE",
            (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rs -> rs.next(), listingId.toString());
        if (!listingExists) return false;
        var existing = jdbc.query("SELECT listing_id, order_id, reason, quantity_delta FROM inventory_movement WHERE business_key = ? FOR UPDATE",
            rs -> rs.next() ? new Existing(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)) : null, key);
        int delta = restore ? quantity : -quantity;
        if (existing != null) {
            if (!existing.listingId.equals(listingId.toString())
                || !Objects.equals(existing.orderId, orderId == null ? null : orderId.toString())
                || !existing.reason.equals(reason) || existing.delta != delta) {
                throw new IllegalArgumentException("库存业务键与请求不一致");
            }
            return true;
        }
        try {
            jdbc.update("INSERT INTO inventory_movement (id, business_key, listing_id, order_id, reason, quantity_delta, created_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))",
                UUID.randomUUID().toString(), key, listingId.toString(), orderId == null ? null : orderId.toString(), reason, delta);
        } catch (DuplicateKeyException duplicate) {
            var raced = jdbc.query("SELECT listing_id, order_id, reason, quantity_delta FROM inventory_movement WHERE business_key = ? FOR UPDATE",
                rs -> rs.next() ? new Existing(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)) : null, key);
            if (raced == null || !raced.listingId.equals(listingId.toString())
                || !Objects.equals(raced.orderId, orderId == null ? null : orderId.toString())
                || !raced.reason.equals(reason) || raced.delta != delta) {
                throw new IllegalArgumentException("库存业务键与请求不一致");
            }
            return true;
        }
        String sql = restore
            ? "UPDATE listing SET available_quantity = available_quantity + ?, status = CASE WHEN status = 'SOLD_OUT' THEN 'ON_SALE' ELSE status END, version = version + 1, updated_at = ? WHERE id = ?"
            : (reason.equals("RETURN_QUARANTINE")
                ? "UPDATE listing SET quarantined_quantity = quarantined_quantity + ?, version = version + 1, updated_at = ? WHERE id = ?"
                : "UPDATE listing SET status = CASE WHEN available_quantity - ? = 0 THEN 'SOLD_OUT' ELSE status END, available_quantity = available_quantity - ?, version = version + 1, updated_at = ? WHERE id = ? AND status = 'ON_SALE' AND available_quantity >= ?");
        int changed;
        if (restore) {
            changed = jdbc.update(sql, quantity, Timestamp.from(Instant.now()), listingId.toString());
        } else if (reason.equals("RETURN_QUARANTINE")) {
            changed = jdbc.update(sql, quantity, Timestamp.from(Instant.now()), listingId.toString());
        } else {
            changed = jdbc.update(sql, quantity, quantity, Timestamp.from(Instant.now()), listingId.toString(), quantity);
        }
        if (changed != 1) {
            jdbc.update("DELETE FROM inventory_movement WHERE business_key = ?", key);
            return false;
        }
        Long version = jdbc.queryForObject("SELECT version FROM listing WHERE id=?", Long.class, listingId.toString());
        if (version != null && version > 0) searchOutbox.enqueue(listingId, version, "INVENTORY_CHANGED");
        return true;
    }

    private static void validate(int quantity, String key) {
        if (quantity <= 0) throw new IllegalArgumentException("库存数量必须为正数");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("库存业务键不能为空");
    }

    private record Existing(String listingId, String orderId, String reason, int delta) { }
}
