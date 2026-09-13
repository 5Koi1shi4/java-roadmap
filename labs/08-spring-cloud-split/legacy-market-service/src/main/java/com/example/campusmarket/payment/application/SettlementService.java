package com.example.campusmarket.payment.application;

import com.example.campusmarket.shared.infrastructure.SellerBalanceLockRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 普通争议净结算；质保期限不参与这条七天结算门禁。 */
@Service
@Profile("!test")
public class SettlementService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SellerBalanceLockRepository sellerLocks;

    /** 兼容仍直接提供加锁前依赖的调用方。 */
    public SettlementService(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this(jdbc, transactionManager, jdbc == null ? null : new SellerBalanceLockRepository(jdbc));
    }

    @Autowired
    public SettlementService(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager transactionManager,
                             SellerBalanceLockRepository sellerLocks) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
        this.sellerLocks = Objects.requireNonNull(sellerLocks, "卖家锁存储不能为空");
    }

    public SettlementResult settle(UUID orderId) {
        Objects.requireNonNull(orderId, "订单ID不能为空");
        UUID seller = findSeller(orderId);
        if (seller == null) throw new IllegalArgumentException("订单不存在");
        return transactions.execute(status -> settleLocked(orderId, seller));
    }

    public boolean canSettle(UUID orderId) {
        Objects.requireNonNull(orderId, "订单ID不能为空");
        return transactions.execute(status -> {
            var row = lockOrder(orderId);
            return row != null && eligible(row, databaseNow());
        });
    }

    private SettlementResult settleLocked(UUID orderId, UUID seller) {
        // 所有结算余额写入路径固定按卖家 → 订单 → 支付 → 结算 → 义务/限制加锁。
        sellerLocks.lock(seller);
        OrderFacts order = lockOrder(orderId);
        if (order == null) throw new IllegalArgumentException("订单不存在");
        if (!seller.equals(order.sellerId())) throw new IllegalStateException("订单卖家归属发生变化");
        if ("SETTLED".equals(order.status())) return existing(orderId);
        Instant now = databaseNow();
        if (!eligible(order, now)) return new SettlementResult(orderId, "BLOCKED", 0L, blockReason(order, now));
        // 结算采用与退款、质保一致的支付事实：只取最新一条成功尝试，
        // 并且只使用该行的退款累计值。
        PaymentFacts payment = jdbc.query("SELECT paid_amount_fen,successful_refund_fen,reserved_refund_fen FROM payment_order WHERE order_id=? AND status='SUCCEEDED' ORDER BY created_at DESC,id DESC LIMIT 1 FOR UPDATE",
            rs -> rs.next() ? new PaymentFacts(rs.getLong(1), rs.getLong(2), rs.getLong(3)) : new PaymentFacts(0, 0, 0), orderId.toString());
        long net = payment.paidAmountFen() - payment.successfulRefundFen();
        if (net < 0) return new SettlementResult(orderId, "BLOCKED", 0L, "REFUND_EXCEEDS_PAID");
        UUID settlementId = UUID.nameUUIDFromBytes(("settlement:" + orderId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO settlement (id,order_id,paid_amount_fen,successful_refund_fen,net_settlement_fen,status,created_at,settled_at) VALUES (?,?,?,?,?,'SETTLED',?,?) ON DUPLICATE KEY UPDATE status='SETTLED',net_settlement_fen=VALUES(net_settlement_fen),settled_at=VALUES(settled_at)",
            settlementId.toString(), orderId.toString(), payment.paidAmountFen(), payment.successfulRefundFen(), net, Timestamp.from(now), Timestamp.from(now));
        applySellerObligations(orderId, settlementId, net, now, seller);
        long availableAfterObligations = jdbc.queryForObject("SELECT net_settlement_fen FROM settlement WHERE id=?", Long.class, settlementId.toString());
        jdbc.update("UPDATE trade_order SET status='SETTLED',version=version+1,updated_at=? WHERE id=? AND status='AFTERSALE_WINDOW'", Timestamp.from(now), orderId.toString());
        UUID eventId = UUID.nameUUIDFromBytes(("settlement-created:" + orderId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String payload = "{\"settlementId\":\"" + settlementId + "\",\"orderId\":\"" + orderId
            + "\",\"netSettlementFen\":" + availableAfterObligations + "}";
        jdbc.update("INSERT INTO integration_outbox (id,event_id,event_type,aggregate_id,aggregate_version,schema_version,occurred_at,payload,status,attempt_count,available_at,created_at) "
                + "VALUES (?,?, 'SETTLEMENT_CREATED',?,?,1,CURRENT_TIMESTAMP(6),CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id",
            eventId.toString(), eventId.toString(), orderId.toString(), 1L, payload);
        return new SettlementResult(orderId, "SETTLED", availableAfterObligations, null);
    }

    private boolean eligible(OrderFacts order, Instant now) {
        if (!"AFTERSALE_WINDOW".equals(order.status())) return false;
        if (order.t0() == null || now.isBefore(order.t0().plus(Duration.ofDays(7)))) return false;
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM dispute_case WHERE order_id=? AND status IN ('OPEN','SELLER_RESPONDED','UNDER_REVIEW','ESCALATED')", Integer.class, order.id().toString());
        if (active != null && active > 0) return false;
        Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM refund_order WHERE order_id=? AND status IN ('REQUESTED','PROCESSING','UNKNOWN')", Integer.class, order.id().toString());
        if (pending != null && pending > 0) return false;
        Integer unreconciled = jdbc.queryForObject("SELECT COUNT(*) FROM refund_order f JOIN return_case r ON r.order_id=f.order_id AND (r.refund_id=f.id OR (r.refund_id IS NULL AND (f.idempotency_key=CONCAT('dispute-return-',r.dispute_case_id) OR f.idempotency_key=CONCAT('dispute-hard-refund-',r.dispute_case_id)))) WHERE f.order_id=? AND f.status='SUCCEEDED' AND (COALESCE(r.refund_status,'')<>'SUCCEEDED' OR (r.resolution_type='RETURN_AND_REFUND' AND r.quarantined_at IS NULL))", Integer.class, order.id().toString());
        if (unreconciled != null && unreconciled > 0) return false;
        Long reserved = jdbc.queryForObject("SELECT reserved_refund_fen FROM payment_order WHERE order_id=? AND status='SUCCEEDED' ORDER BY created_at DESC,id DESC LIMIT 1", Long.class, order.id().toString());
        return reserved == null || reserved == 0;
    }

    private String blockReason(OrderFacts order, Instant now) {
        if (!"AFTERSALE_WINDOW".equals(order.status())) return "ORDER_STATUS";
        if (order.t0() == null || now.isBefore(order.t0().plus(Duration.ofDays(7)))) return "TRIAL_WINDOW";
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM dispute_case WHERE order_id=? AND status IN ('OPEN','SELLER_RESPONDED','UNDER_REVIEW','ESCALATED')", Integer.class, order.id().toString());
        if (active != null && active > 0) return "ACTIVE_DISPUTE";
        Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM refund_order WHERE order_id=? AND status IN ('REQUESTED','PROCESSING','UNKNOWN')", Integer.class, order.id().toString());
        if (pending != null && pending > 0) return "PENDING_REFUND";
        Integer unreconciled = jdbc.queryForObject("SELECT COUNT(*) FROM refund_order f JOIN return_case r ON r.order_id=f.order_id AND (r.refund_id=f.id OR (r.refund_id IS NULL AND (f.idempotency_key=CONCAT('dispute-return-',r.dispute_case_id) OR f.idempotency_key=CONCAT('dispute-hard-refund-',r.dispute_case_id)))) WHERE f.order_id=? AND f.status='SUCCEEDED' AND (COALESCE(r.refund_status,'')<>'SUCCEEDED' OR (r.resolution_type='RETURN_AND_REFUND' AND r.quarantined_at IS NULL))", Integer.class, order.id().toString());
        if (unreconciled != null && unreconciled > 0) return "UNRECONCILED_RETURN";
        return "REFUND_RESERVATION";
    }

    private SettlementResult existing(UUID orderId) {
        return jdbc.query("SELECT net_settlement_fen,status FROM settlement WHERE order_id=?", rs -> rs.next()
            ? new SettlementResult(orderId, rs.getString(2), rs.getLong(1), null)
            : new SettlementResult(orderId, "SETTLED", 0, null), orderId.toString());
    }

    private UUID findSeller(UUID orderId) {
        return jdbc.query("SELECT seller_id FROM trade_order WHERE id=?", rs -> rs.next()
            ? UUID.fromString(rs.getString(1)) : null, orderId.toString());
    }

    private OrderFacts lockOrder(UUID orderId) {
        return jdbc.query("SELECT id,seller_id,status,t0 FROM trade_order WHERE id=? FOR UPDATE", rs -> rs.next()
            ? new OrderFacts(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), rs.getString(3),
                rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()) : null, orderId.toString());
    }
    private Instant databaseNow() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class).toInstant(); }

    /** 未来结算按最早到期义务抵扣；唯一业务键防止同一结算重复扣款。 */
    private void applySellerObligations(UUID orderId, UUID settlementId, long net, Instant now, UUID seller) {
        long remaining = net;
        var obligations = jdbc.query("SELECT id,warranty_case_id,obligation_amount_fen,funded_amount_fen,version FROM seller_obligation WHERE seller_id=? AND status IN ('AWAITING_FUNDING','PARTIALLY_FUNDED','CANCELLED') ORDER BY funding_deadline,id FOR UPDATE",
            (rs, n) -> new Obligation(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), rs.getLong(3), rs.getLong(4), rs.getLong(5)), seller.toString());
        for (Obligation obligation : obligations) {
            if (remaining <= 0) break;
            long due = Math.min(remaining, obligation.amount() - obligation.funded());
            if (due <= 0) continue;
            if (jdbc.update("INSERT INTO settlement_obligation_deduction(id,settlement_id,obligation_id,amount_fen,created_at) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                UUID.randomUUID().toString(), settlementId.toString(), obligation.id().toString(), due, Timestamp.from(now)) == 1) {
                long funded = obligation.funded() + due;
                if (jdbc.update("UPDATE seller_obligation SET funded_amount_fen=?,status=?,restriction_status=?,version=version+1,updated_at=? WHERE id=? AND funded_amount_fen=?",
                    funded, funded == obligation.amount() ? "FUNDED" : "PARTIALLY_FUNDED", funded == obligation.amount() ? "NONE" : "RESTRICTED", Timestamp.from(now), obligation.id().toString(), obligation.funded()) != 1)
                    throw new IllegalStateException("义务抵扣状态冲突");
                if (funded == obligation.amount()) {
                    var types=jdbc.query("SELECT restriction_type FROM seller_account_restriction WHERE source_obligation_id=? AND status='ACTIVE' FOR UPDATE",(rs,n)->rs.getString(1),obligation.id().toString());
                    jdbc.update("UPDATE seller_account_restriction SET status='CLEARED',cleared_at=? WHERE source_obligation_id=? AND status='ACTIVE'", Timestamp.from(now), obligation.id().toString());
                    for(String type:types) recordTransition(seller,"SELLER_RESTRICTION_CLEARED",obligation.id(),obligation.version()+1,"{\"restrictionType\":\""+type+"\"}",now);
                }
                UUID auditId=UUID.nameUUIDFromBytes(("settlement-obligation:"+settlementId+":"+obligation.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String auditDetails="{\"settlementId\":\""+settlementId+"\",\"amountFen\":"+due+"}";
                jdbc.update("INSERT INTO audit_event(id,actor_id,action,resource_type,resource_id,result,details,occurred_at) VALUES (?,?, 'WARRANTY_OBLIGATION_DEDUCTED','SELLER_OBLIGATION',?,'SUCCESS',CAST(? AS JSON),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id",auditId.toString(),seller.toString(),obligation.id().toString(),auditDetails);
                if (funded == obligation.amount()) {
                    UUID event=UUID.nameUUIDFromBytes(("warranty-refund:"+obligation.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    String payload="{\"orderId\":\""+orderId+"\",\"caseId\":\""+obligation.caseId()+"\",\"obligationId\":\""+obligation.id()+"\",\"amountFen\":"+obligation.amount()+"}";
                    jdbc.update("INSERT INTO integration_outbox(id,event_id,event_type,aggregate_id,aggregate_version,schema_version,occurred_at,payload,status,attempt_count,available_at,created_at) VALUES (?,?, 'WARRANTY_REFUND_REQUESTED',?,?,1,CURRENT_TIMESTAMP(6),CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id",event.toString(),event.toString(),obligation.id().toString(),obligation.version()+1,payload);
                }
                remaining -= due;
            }
        }
        if (remaining != net) jdbc.update("UPDATE settlement SET net_settlement_fen=? WHERE id=?", remaining, settlementId.toString());
    }

    private void recordTransition(UUID seller,String action,UUID obligation,long version,String payload,Instant now){
        UUID event=UUID.nameUUIDFromBytes((action+":"+obligation+":"+version+":"+payload).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO audit_event(id,actor_id,action,resource_type,resource_id,result,details,occurred_at) VALUES (?,?,?,'SELLER_OBLIGATION',?,'SUCCESS',CAST(? AS JSON),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id",event.toString(),seller.toString(),action,obligation.toString(),payload);
        jdbc.update("INSERT INTO integration_outbox(id,event_id,event_type,aggregate_id,aggregate_version,schema_version,occurred_at,payload,status,attempt_count,available_at,created_at) VALUES (?,?,?, ?,?,1,CURRENT_TIMESTAMP(6),CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id",event.toString(),event.toString(),action,obligation.toString(),version,payload);
    }

    public record SettlementResult(UUID orderId, String status, long netSettlementFen, String blockedReason) {}
    private record OrderFacts(UUID id, UUID sellerId, String status, Instant t0) {}
    private record PaymentFacts(long paidAmountFen, long successfulRefundFen, long reservedRefundFen) {}
    private record Obligation(UUID id, UUID caseId, long amount, long funded, long version) {}
}
