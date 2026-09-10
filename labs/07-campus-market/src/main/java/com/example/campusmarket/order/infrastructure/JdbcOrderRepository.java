package com.example.campusmarket.order.infrastructure;

import com.example.campusmarket.catalog.domain.ListingStatus;
import com.example.campusmarket.catalog.domain.WarrantyTerm;
import com.example.campusmarket.order.application.CreateOrderService;
import com.example.campusmarket.order.domain.TradeOrder;
import com.example.campusmarket.shared.Money;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;

@Repository
@Profile("!test")
public class JdbcOrderRepository {
    private final JdbcTemplate jdbc;

    public JdbcOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
    }

    public CommandLock lockOrCreateCommand(UUID actorId, String key, byte[] requestHash) {
        jdbc.update("""
            INSERT INTO order_command (id, actor_id, idempotency_key, request_hash, status, created_at)
            VALUES (?, ?, ?, ?, 'PROCESSING', CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE id=id
            """, UUID.randomUUID().toString(), actorId.toString(), key, requestHash);
        return jdbc.query("SELECT id, order_id, request_hash, status, response_utf8 FROM order_command WHERE actor_id=? AND idempotency_key=? FOR UPDATE",
            rs -> {
                if (!rs.next()) throw new IllegalStateException("幂等命令锁定失败");
                String response = rs.getString("response_utf8");
                return new CommandLock(UUID.fromString(rs.getString("id")),
                    rs.getString("order_id") == null ? null : UUID.fromString(rs.getString("order_id")),
                    rs.getBytes("request_hash"), "COMPLETED".equals(rs.getString("status")),
                    response == null ? null : response.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }, actorId.toString(), key);
    }

    public void completeCommand(UUID commandId, byte[] responseUtf8, UUID orderId) {
        int changed = jdbc.update("UPDATE order_command SET order_id=?, status='COMPLETED', response_utf8=?, completed_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='PROCESSING'",
            orderId == null ? null : orderId.toString(), new String(responseUtf8, java.nio.charset.StandardCharsets.UTF_8), commandId.toString());
        if (changed != 1) throw new IllegalStateException("幂等命令完成失败");
    }

    public CreateOrderService.ListingForOrder lockListing(UUID listingId) {
        return jdbc.query("SELECT id,seller_id,title,description,category,unit_price_fen,warranty_days,warranty_scope,manufacturer_warranty_proof_snapshot,manufacturer_warranty_expires_at,status FROM listing WHERE id=? FOR UPDATE",
            rs -> {
                if (!rs.next()) return null;
                return new CreateOrderService.ListingForOrder(
                    UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("seller_id")),
                    rs.getString("title"), rs.getString("description"), rs.getString("category"),
                    Money.ofFen(rs.getLong("unit_price_fen")), new WarrantyTerm((Integer) rs.getObject("warranty_days")),
                    rs.getString("warranty_scope"), rs.getString("manufacturer_warranty_proof_snapshot"),
                    timestamp(rs.getTimestamp("manufacturer_warranty_expires_at")),
                    ListingStatus.valueOf(rs.getString("status")));
            }, listingId.toString());
    }

    public Instant currentDatabaseTime() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", (rs, rowNum) -> rs.getTimestamp(1).toInstant());
    }

    public void insertOrder(TradeOrder order) {
        TradeOrder.ListingSnapshot snapshot = order.snapshot();
        jdbc.update("""
            INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,
                unit_price_fen,quantity,total_amount_fen,warranty_days,warranty_scope_snapshot,
                manufacturer_warranty_proof_snapshot,manufacturer_warranty_expires_at,payment_deadline,
                paid_amount_fen,status,version,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,0,?,?)
            """, order.id().toString(), order.buyerId().toString(), order.sellerId().toString(), snapshot.listingId().toString(),
            snapshot.title(), snapshot.description(), snapshot.unitPrice().fen(), order.quantity(), order.totalAmount().fen(),
            snapshot.warrantyTerm().sellerWarrantyDays(), snapshot.warrantyScope(), snapshot.manufacturerWarrantyProof(),
            timestamp(snapshot.manufacturerWarrantyExpiresAt()), timestamp(order.paymentDeadline()), order.status().name(),
            timestamp(order.createdAt()), timestamp(order.createdAt()));
        jdbc.update("INSERT INTO order_deadline_claim (id,order_id,deadline_type,status,due_at) VALUES (?,?, 'PAYMENT','NEW',?)",
            UUID.randomUUID().toString(), order.id().toString(), timestamp(order.paymentDeadline()));
    }

    public void insertOrderCreatedOutbox(TradeOrder order, ObjectMapper objectMapper) {
        TradeOrder.ListingSnapshot snapshot = order.snapshot();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("orderId", order.id().toString());
        payload.put("buyerId", order.buyerId().toString());
        payload.put("sellerId", order.sellerId().toString());
        payload.put("listingId", snapshot.listingId().toString());
        payload.put("quantity", order.quantity());
        payload.put("totalAmountFen", order.totalAmount().fen());
        final String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("订单事件序列化失败", e);
        }
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO integration_outbox (id,event_id,event_type,aggregate_id,aggregate_version,schema_version,
                occurred_at,payload,status,attempt_count,available_at,created_at)
            VALUES (?,?, 'ORDER_CREATED', ?,1,1,?,CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            """, UUID.randomUUID().toString(), eventId.toString(), order.id().toString(),
            timestamp(order.createdAt()), json);
    }

    public record CommandLock(UUID id, UUID orderId, byte[] requestHash, boolean completed, byte[] responseUtf8) {
        public CommandLock {
            Objects.requireNonNull(id, "命令ID不能为空");
            Objects.requireNonNull(requestHash, "请求摘要不能为空");
        }
    }

    private static Instant timestamp(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
