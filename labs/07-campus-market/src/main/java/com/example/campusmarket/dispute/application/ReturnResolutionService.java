package com.example.campusmarket.dispute.application;

import com.example.campusmarket.catalog.application.InventoryPort;
import com.example.campusmarket.dispute.domain.DisputeDecision;
import com.example.campusmarket.dispute.domain.ReturnProofType;
import com.example.campusmarket.payment.application.RefundService;
import com.example.campusmarket.shared.Money;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 普通争议退回结算编排；支付网关调用发生在数据库事务之外。 */
@Service
@Profile("!test")
public class ReturnResolutionService {
    private final JdbcTemplate jdbc;
    private final RefundService refunds;
    private final InventoryPort inventory;
    private final TransactionTemplate transactions;

    public ReturnResolutionService(JdbcTemplate jdbc, RefundService refunds, InventoryPort inventory,
                                   org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.refunds = Objects.requireNonNull(refunds, "退款服务不能为空");
        this.inventory = Objects.requireNonNull(inventory, "库存端口不能为空");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
    }

    /** 只接受固定裁决；同一 dispute 的重复调用返回已持久化结果。 */
    public Resolution resolve(UUID disputeCaseId, DisputeDecision decision, int approvedQuantity,
                              ReturnProofType proofType, String proofReference) {
        Objects.requireNonNull(disputeCaseId, "争议ID不能为空");
        Objects.requireNonNull(decision, "裁决不能为空");
        if (decision == DisputeDecision.REJECT) {
            if (approvedQuantity != 0) throw new IllegalArgumentException("驳回不得批准数量");
            return transactions.execute(status -> reject(disputeCaseId));
        }
        if (approvedQuantity <= 0) throw new IllegalArgumentException("批准数量必须为正数");
        if (proofType == ReturnProofType.BUYER_EVIDENCE)
            throw new IllegalStateException("买家证据不能直接触发退款");
        if (proofType == null || proofReference == null || proofReference.isBlank())
            throw new IllegalArgumentException("可信证明不能为空");

        CaseFacts facts = transactions.execute(status -> prepare(disputeCaseId, decision, approvedQuantity, proofType, proofReference));
        if (facts.existingRefundId() != null) return queryResolution(facts);
        RefundService.RefundResult refund = refunds.requestRefund(facts.orderId(),
            "dispute-return-" + disputeCaseId, Money.ofFen(facts.amountFen()), "DISPUTE", disputeCaseId);
        boolean quarantined = "SUCCEEDED".equals(refund.status()) && decision == DisputeDecision.RETURN_AND_REFUND
            && inventory.quarantine(facts.listingId(), approvedQuantity, "return-quarantine-" + disputeCaseId);
        return transactions.execute(status -> finish(facts, refund, quarantined));
    }

    public Resolution resolve(ResolutionRequest request) {
        Objects.requireNonNull(request, "裁决请求不能为空");
        return resolve(request.disputeCaseId(), request.decision(), request.approvedQuantity(),
            request.proofType(), request.proofReference());
    }

    public static long refundAmountFen(long unitPriceFen, int approvedQuantity) {
        if (unitPriceFen <= 0 || approvedQuantity <= 0) throw new IllegalArgumentException("退款参数无效");
        try { return Math.multiplyExact(unitPriceFen, approvedQuantity); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("退款金额溢出", overflow); }
    }

    private CaseFacts prepare(UUID caseId, DisputeDecision decision, int approvedQuantity,
                              ReturnProofType proofType, String proofReference) {
        var row = jdbc.query("SELECT c.order_id,c.disputed_quantity,c.status,o.listing_id,o.unit_price_fen,o.quantity,p.id,p.paid_amount_fen,p.status "
                + "FROM dispute_case c JOIN trade_order o ON o.id=c.order_id "
                + "LEFT JOIN payment_order p ON p.order_id=o.id AND p.status='SUCCEEDED' "
                + "WHERE c.id=? FOR UPDATE", rs -> rs.next() ? new Object[] { UUID.fromString(rs.getString(1)), rs.getInt(2),
            rs.getString(3), UUID.fromString(rs.getString(4)), rs.getLong(5), rs.getInt(6),
                rs.getString(7) == null ? null : UUID.fromString(rs.getString(7)), rs.getLong(8), rs.getString(9) } : null, caseId.toString());
        if (row == null) throw new IllegalArgumentException("争议不存在");
        UUID orderId = (UUID) row[0];
        jdbc.query("SELECT id FROM trade_order WHERE id=? FOR UPDATE",
            (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rs -> rs.next(), orderId.toString());
        String status = (String) row[2];
        if (!"OPEN".equals(status) && !"SELLER_RESPONDED".equals(status) && !"UNDER_REVIEW".equals(status) && !"ESCALATED".equals(status)) {
            var existing = jdbc.query("SELECT refund_id,order_id,listing_id,unit_price_fen,approved_quantity,status FROM return_case WHERE dispute_case_id=?",
                rs -> rs.next() ? new CaseFacts(caseId, orderId, UUID.fromString(rs.getString("listing_id")),
                    rs.getInt("approved_quantity"), rs.getInt("approved_quantity"), rs.getLong("unit_price_fen"),
                    rs.getLong("unit_price_fen") * rs.getInt("approved_quantity"),
                    rs.getString("refund_id") == null ? null : UUID.fromString(rs.getString("refund_id")), rs.getString("status")) : null, caseId.toString());
            if (existing != null) return existing;
            throw new IllegalStateException("争议已经裁决");
        }
        int disputed = (Integer) row[1];
        if (approvedQuantity > disputed) throw new IllegalArgumentException("批准数量超过争议数量");
        Integer used = jdbc.queryForObject("SELECT COALESCE(SUM(approved_quantity),0) FROM dispute_case WHERE order_id=? AND id<>? AND status='RESOLVED'", Integer.class, orderId.toString(), caseId.toString());
        if (used != null && used + approvedQuantity > (Integer) row[5]) throw new IllegalArgumentException("累计裁决数量超过购买数量");
        UUID listingId = (UUID) row[3];
        long unitPriceFen = (Long) row[4];
        long amountFen = refundAmountFen(unitPriceFen, approvedQuantity);
        long paid = (Long) row[7];
        if (amountFen > paid) throw new IllegalArgumentException("退款超过实付金额");
        UUID paymentId = (UUID) row[6];
        if (paymentId == null) throw new IllegalStateException("没有成功支付");
        Instant now = databaseNow();
        UUID returnId = UUID.nameUUIDFromBytes((caseId + "|" + decision.name()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO return_case (id,dispute_case_id,order_id,listing_id,payment_order_id,unit_price_fen,status,proof_type,proof_reference,approved_quantity,deadline,created_at,updated_at) "
                + "VALUES (?,?,?,?,?,?,'CONFIRMED',?,?,?,?,?,?) ON DUPLICATE KEY UPDATE proof_type=VALUES(proof_type),proof_reference=VALUES(proof_reference),updated_at=VALUES(updated_at)",
            returnId.toString(), caseId.toString(), orderId.toString(), listingId.toString(), paymentId.toString(), unitPriceFen,
            proofType.name(), proofReference, approvedQuantity, TimestampValue.of(now.plusSeconds(14 * 86400L)), TimestampValue.of(now), TimestampValue.of(now));
        jdbc.update("UPDATE dispute_case SET decision=?,approved_quantity=?,proof_type=?,proof_reference=?,status='RESOLVED',resolved_at=?,version=version+1,updated_at=? WHERE id=? AND status IN ('OPEN','SELLER_RESPONDED','UNDER_REVIEW','ESCALATED')",
            decision.name(), approvedQuantity, proofType.name(), proofReference, TimestampValue.of(now), TimestampValue.of(now), caseId.toString());
        return new CaseFacts(caseId, orderId, listingId, (Integer) row[5], approvedQuantity, unitPriceFen, amountFen, null, "REQUESTED");
    }

    private Resolution finish(CaseFacts facts, RefundService.RefundResult refund, boolean quarantined) {
        Instant now = databaseNow();
        jdbc.update("UPDATE return_case SET refund_id=?,refund_status=?,status=CASE WHEN ?='SUCCEEDED' THEN 'CONFIRMED' ELSE 'AWAITING_PROOF' END,refunded_at=CASE WHEN ?='SUCCEEDED' THEN ? ELSE refunded_at END,quarantined_at=CASE WHEN ? THEN ? ELSE quarantined_at END,updated_at=? WHERE dispute_case_id=?",
            refund.refundId().toString(), refund.status(), refund.status(), refund.status(), TimestampValue.of(now), quarantined, TimestampValue.of(now), TimestampValue.of(now), facts.caseId().toString());
        if ("SUCCEEDED".equals(refund.status())) {
            String target = facts.approvedQuantity() == facts.purchasedQuantity() ? "REFUNDED" : "AFTERSALE_WINDOW";
            jdbc.update("UPDATE trade_order SET status=?,version=version+1,updated_at=? WHERE id=? AND status IN ('DISPUTED','AFTERSALE_WINDOW','REFUNDING_CANCEL')",
                target, TimestampValue.of(now), facts.orderId().toString());
        }
        return new Resolution(facts.caseId(), refund.refundId(), refund.status(), facts.amountFen(), quarantined);
    }

    private Resolution reject(UUID caseId) {
        int changed = jdbc.update("UPDATE dispute_case SET status='REJECTED',decision='REJECT',approved_quantity=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('OPEN','SELLER_RESPONDED','UNDER_REVIEW','ESCALATED')", caseId.toString());
        if (changed != 1) throw new IllegalStateException("争议已经裁决");
        return new Resolution(caseId, null, "REJECTED", 0, false);
    }

    private Resolution queryResolution(CaseFacts facts) {
        var row = jdbc.query("SELECT refund_id,refund_status,quarantined_at FROM return_case WHERE dispute_case_id=?",
            rs -> rs.next() ? new Object[] { UUID.fromString(rs.getString(1)), rs.getString(2), rs.getTimestamp(3) != null } : null, facts.caseId().toString());
        return row == null ? new Resolution(facts.caseId(), null, "REQUESTED", facts.amountFen(), false)
            : new Resolution(facts.caseId(), (UUID) row[0], (String) row[1], facts.amountFen(), (Boolean) row[2]);
    }

    private Instant databaseNow() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", java.sql.Timestamp.class).toInstant(); }
    public record Resolution(UUID disputeCaseId, UUID refundId, String refundStatus, long amountFen, boolean quarantined) {}
    public record ResolutionRequest(UUID disputeCaseId, DisputeDecision decision, int approvedQuantity,
                                    ReturnProofType proofType, String proofReference) {}
    private record CaseFacts(UUID caseId, UUID orderId, UUID listingId, int purchasedQuantity, int approvedQuantity, long unitPriceFen,
                             long amountFen, UUID existingRefundId, String refundStatus) {}
    private static final class TimestampValue {
        private TimestampValue() {}
        static java.sql.Timestamp of(Instant value) { return java.sql.Timestamp.from(value); }
    }
}
