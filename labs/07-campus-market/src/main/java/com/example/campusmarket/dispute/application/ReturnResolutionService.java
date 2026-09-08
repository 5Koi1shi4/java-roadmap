package com.example.campusmarket.dispute.application;

import com.example.campusmarket.catalog.application.InventoryPort;
import com.example.campusmarket.dispute.domain.DisputeDecision;
import com.example.campusmarket.dispute.domain.ReturnProofType;
import com.example.campusmarket.dispute.infrastructure.JdbcDisputeRepository;
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
    private final JdbcDisputeRepository disputeRepository;
    private final TransactionTemplate transactions;

    public ReturnResolutionService(JdbcTemplate jdbc, RefundService refunds, InventoryPort inventory,
                                   JdbcDisputeRepository disputeRepository,
                                   org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.refunds = Objects.requireNonNull(refunds, "退款服务不能为空");
        this.inventory = Objects.requireNonNull(inventory, "库存端口不能为空");
        this.disputeRepository = Objects.requireNonNull(disputeRepository, "争议仓储不能为空");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
    }

    /** 只接受固定裁决；同一 dispute 的重复调用返回已持久化结果。 */
    public Resolution resolve(UUID disputeCaseId, DisputeDecision decision, int approvedQuantity,
                              ReturnProofType proofType, String proofReference) {
        if (decision == DisputeDecision.REJECT) return resolve(disputeCaseId, decision, approvedQuantity, proofType, proofReference, null);
        throw new IllegalStateException("可信退款必须携带受控证明授权");
    }

    public Resolution resolve(UUID disputeCaseId, DisputeDecision decision, int approvedQuantity,
                              ReturnProofType proofType, String proofReference, ProofAuthority authority) {
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
        Objects.requireNonNull(authority, "证明授权不能为空");

        CaseFacts facts = transactions.execute(status -> prepare(disputeCaseId, decision, approvedQuantity, proofType, proofReference, authority));
        if (facts.existingRefundId() != null) return queryResolution(facts);
        RefundService.RefundResult refund = refunds.requestRefund(facts.orderId(),
            "dispute-return-" + disputeCaseId, Money.ofFen(facts.amountFen()), "DISPUTE", disputeCaseId);
        Resolution pending = transactions.execute(status -> finish(facts, decision, refund));
        if ("FAILED".equals(refund.status())) {
            transactions.execute(status -> {
                jdbc.update("UPDATE dispute_case SET status='ESCALATED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='RESOLVED'", disputeCaseId.toString());
                return null;
            });
        }
        return "SUCCEEDED".equals(refund.status()) ? reconcileSuccessfulRefund(refund.refundId()) : pending;
    }

    public Resolution resolve(ResolutionRequest request) {
        Objects.requireNonNull(request, "裁决请求不能为空");
        return resolve(request.disputeCaseId(), request.decision(), request.approvedQuantity(),
            request.proofType(), request.proofReference(), request.authority());
    }

    /** 卖家确认管理员退回裁决；调用方必须先经过 HTTP 幂等命令边界。 */
    public Resolution confirmSellerReturn(UUID disputeCaseId, UUID sellerId, String proofReference) {
        Objects.requireNonNull(disputeCaseId, "争议ID不能为空");
        Objects.requireNonNull(sellerId, "卖家ID不能为空");
        if (proofReference == null || proofReference.isBlank()) throw new IllegalArgumentException("退回证明不能为空");
        SellerReturnFacts facts = jdbc.query("SELECT c.order_id,c.decision,c.approved_quantity,o.seller_id "
                + "FROM dispute_case c JOIN trade_order o ON o.id=c.order_id WHERE c.id=?",
            rs -> rs.next() ? new SellerReturnFacts(UUID.fromString(rs.getString(1)),
                rs.getString(2) == null ? null : DisputeDecision.valueOf(rs.getString(2)),
                (Integer) rs.getObject(3), UUID.fromString(rs.getString(4))) : null, disputeCaseId.toString());
        if (facts == null) throw new NotFoundException();
        if (!sellerId.equals(facts.sellerId())) throw new ForbiddenException();
        if (facts.decision() != DisputeDecision.RETURN_AND_REFUND || facts.approvedQuantity() == null
                || facts.approvedQuantity() <= 0) throw new IllegalStateException("当前争议不允许卖家确认退回");
        return resolve(disputeCaseId, facts.decision(), facts.approvedQuantity(), ReturnProofType.SELLER_CONFIRMED,
            proofReference, ProofAuthority.seller(sellerId));
    }

    /** 硬期限任务提交的退款意图在截止事务外执行；失败或进程崩溃可由收敛器重试。 */
    public Resolution executeHardDeadlineRefund(UUID disputeCaseId) {
        Objects.requireNonNull(disputeCaseId, "争议ID不能为空");
        HardFacts facts = transactions.execute(status -> jdbc.query("SELECT r.refund_id,r.order_id,r.unit_price_fen,r.approved_quantity,r.refund_status,r.resolution_type "
                + "FROM return_case r WHERE r.dispute_case_id=? AND r.status='REQUESTED' FOR UPDATE",
            rs -> rs.next() ? new HardFacts(rs.getString(1) == null ? null : UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                rs.getLong(3), rs.getInt(4), rs.getString(5), rs.getString(6)) : null, disputeCaseId.toString()));
        if (facts == null) return null;
        long amount = refundAmountFen(facts.unitPriceFen(), facts.approvedQuantity());
        String key = "dispute-hard-refund-" + disputeCaseId;
        RefundService.RefundResult refund = facts.refundId() == null
            ? refunds.requestRefund(facts.orderId(), key, Money.ofFen(amount), "DISPUTE", disputeCaseId)
            : refunds.queryRefund(facts.refundId());
        if (refund == null) return null;
        if ("FAILED".equals(refund.status())) {
            transactions.execute(status -> {
                jdbc.update("UPDATE return_case SET refund_id=?,refund_status='FAILED',status='ESCALATED',updated_at=CURRENT_TIMESTAMP(6) WHERE dispute_case_id=? AND status='REQUESTED'", refund.refundId().toString(), disputeCaseId.toString());
                jdbc.update("UPDATE dispute_case SET status='ESCALATED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='RESOLVED'", disputeCaseId.toString());
                return null;
            });
            return new Resolution(disputeCaseId, refund.refundId(), "FAILED", amount, false);
        }
        transactions.execute(status -> {
            jdbc.update("UPDATE return_case SET refund_id=?,refund_status=?,status='CONFIRMED',updated_at=CURRENT_TIMESTAMP(6) WHERE dispute_case_id=? AND status='REQUESTED' AND (refund_id IS NULL OR refund_id=?)",
                refund.refundId().toString(), refund.status(), disputeCaseId.toString(), refund.refundId().toString());
            return null;
        });
        return "SUCCEEDED".equals(refund.status()) ? reconcileSuccessfulRefund(refund.refundId())
            : new Resolution(disputeCaseId, refund.refundId(), refund.status(), amount, false);
    }

    /** 人工队列中的硬期限失败退款恢复；只允许查询到提供方成功事实后重新占额并收敛。 */
    public Resolution retryHardDeadlineRefund(UUID disputeCaseId) {
        return retryFailedRefund(disputeCaseId);
    }

    /** 普通裁决与硬期限共用的人工恢复入口；始终复用原退款 ID 与提供方 reference。 */
    public Resolution retryFailedRefund(UUID disputeCaseId) {
        Objects.requireNonNull(disputeCaseId, "争议ID不能为空");
        UUID refundId = jdbc.query("SELECT refund_id FROM return_case WHERE dispute_case_id=? AND status='ESCALATED' AND refund_status='FAILED'",
            rs -> rs.next() && rs.getString(1) != null ? UUID.fromString(rs.getString(1)) : null, disputeCaseId.toString());
        if (refundId == null) throw new IllegalStateException("没有可恢复的硬期限退款");
        RefundService.RefundResult refund = refunds.retryFailedRefund(refundId);
        long amount = jdbc.queryForObject("SELECT amount_fen FROM refund_order WHERE id=?", Long.class, refundId.toString());
        if (!"SUCCEEDED".equals(refund.status())) return new Resolution(disputeCaseId, refundId, refund.status(), amount, false);
        return reconcileSuccessfulRefund(refundId);
    }

    /** 恢复已提交但尚未关联的普通退款意图；不存在提供方记录时不凭空新建退款。 */
    public int recoverPendingPreparedReturns(int limit) {
        if (limit <= 0 || limit > 1000) throw new IllegalArgumentException("退回恢复批量大小必须在1到1000之间");
        var pending = jdbc.query("SELECT r.dispute_case_id,r.order_id,r.unit_price_fen,r.approved_quantity,r.resolution_type,r.status "
                + "FROM return_case r WHERE r.status IN ('CONFIRMED','ESCALATED') "
                + "AND (r.refund_id IS NULL OR r.refund_status IN ('REQUESTED','PROCESSING','UNKNOWN')) "
                + "ORDER BY r.updated_at,r.dispute_case_id LIMIT ?",
            (rs, n) -> new PendingReturn(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                rs.getLong(3), rs.getInt(4), rs.getString(5), rs.getString(6)), limit);
        int recovered = 0;
        for (PendingReturn pendingReturn : pending) {
            String ordinaryKey = "dispute-return-" + pendingReturn.caseId();
            String hardDeadlineKey = "dispute-hard-refund-" + pendingReturn.caseId();
            String key = "RETURN_AND_REFUND".equals(pendingReturn.resolutionType()) ? hardDeadlineKey : ordinaryKey;
            UUID candidate = jdbc.query("SELECT f.id FROM refund_order f WHERE f.order_id=? AND f.idempotency_key IN (?,?) ORDER BY f.created_at LIMIT 1",
                rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null, pendingReturn.orderId().toString(), ordinaryKey, hardDeadlineKey);
            if (candidate == null && "CONFIRMED".equals(pendingReturn.status())) {
                RefundService.RefundResult requested = refunds.requestRefund(pendingReturn.orderId(), key,
                    Money.ofFen(refundAmountFen(pendingReturn.unitPriceFen(), pendingReturn.approvedQuantity())),
                    "DISPUTE", pendingReturn.caseId());
                candidate = requested == null ? null : requested.refundId();
            }
            if (candidate != null) { reconcileSuccessfulRefund(candidate); recovered++; }
        }
        return recovered;
    }

    public static long refundAmountFen(long unitPriceFen, int approvedQuantity) {
        if (unitPriceFen <= 0 || approvedQuantity <= 0) throw new IllegalArgumentException("退款参数无效");
        try { return Math.multiplyExact(unitPriceFen, approvedQuantity); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("退款金额溢出", overflow); }
    }

    private CaseFacts prepare(UUID caseId, DisputeDecision decision, int approvedQuantity,
                              ReturnProofType proofType, String proofReference, ProofAuthority authority) {
        UUID orderId = jdbc.query("SELECT order_id FROM dispute_case WHERE id=?",
            rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null, caseId.toString());
        if (orderId == null) throw new IllegalArgumentException("争议不存在");
        jdbc.query("SELECT id FROM trade_order WHERE id=? FOR UPDATE",
            (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rs -> rs.next(), orderId.toString());
        var row = jdbc.query("SELECT c.order_id,c.disputed_quantity,c.status,o.listing_id,o.unit_price_fen,o.quantity,p.id,p.paid_amount_fen,p.provider,p.status "
                + "FROM dispute_case c JOIN trade_order o ON o.id=c.order_id "
                + "LEFT JOIN payment_order p ON p.order_id=o.id AND p.status='SUCCEEDED' "
                + "WHERE c.id=? FOR UPDATE", rs -> rs.next() ? new Object[] { UUID.fromString(rs.getString(1)), rs.getInt(2),
            rs.getString(3), UUID.fromString(rs.getString(4)), rs.getLong(5), rs.getInt(6),
                rs.getString(7) == null ? null : UUID.fromString(rs.getString(7)), rs.getLong(8), rs.getString(9), rs.getString(10) } : null, caseId.toString());
        if (row == null) throw new IllegalArgumentException("争议不存在");
        orderId = (UUID) row[0];
        UUID lockedOrderId = orderId;
        String status = (String) row[2];
        // 先读取不可变的退回事实。管理员裁决已提交但退款仍在外部处理时，任何重试
        // 都必须返回原事实，不能用迟到证明覆盖 proof/reference 或 resolution 元数据。
        var existing = jdbc.query("SELECT refund_id,order_id,listing_id,unit_price_fen,approved_quantity,status FROM return_case WHERE dispute_case_id=? FOR UPDATE",
            rs -> rs.next() ? new CaseFacts(caseId, lockedOrderId, UUID.fromString(rs.getString("listing_id")),
                rs.getInt("approved_quantity"), rs.getInt("approved_quantity"), rs.getLong("unit_price_fen"),
                rs.getLong("unit_price_fen") * rs.getInt("approved_quantity"),
                rs.getString("refund_id") == null ? null : UUID.fromString(rs.getString("refund_id")), rs.getString("status")) : null, caseId.toString());
        if (existing != null) return existing;
        // 管理员 HTTP 裁决先将争议写成 RESOLVED；没有既有 return_case 时允许该状态继续
        // prepare，保证 HTTP 裁决与异步退款之间可恢复。
        if (!"OPEN".equals(status) && !"SELLER_RESPONDED".equals(status) && !"UNDER_REVIEW".equals(status) && !"ESCALATED".equals(status) && !"RESOLVED".equals(status)) {
            throw new IllegalStateException("争议已经裁决");
        }
        int disputed = (Integer) row[1];
        if (approvedQuantity > disputed) throw new IllegalArgumentException("批准数量超过争议数量");
        UUID sellerId = jdbc.queryForObject("SELECT seller_id FROM trade_order WHERE id=?", (rs, n) -> UUID.fromString(rs.getString(1)), orderId.toString());
        UUID assignedAdminId = jdbc.query("SELECT assigned_admin_id FROM dispute_case WHERE id=?",
            rs -> rs.next() && rs.getString(1) != null ? UUID.fromString(rs.getString(1)) : null, caseId.toString());
        String provider = (String) row[8];
        authorizeProof(authority, proofType, proofReference, orderId, approvedQuantity, sellerId, assignedAdminId, provider);
        int used = disputeRepository.cumulativeReservedQuantity(orderId, caseId);
        if (used + approvedQuantity > (Integer) row[5]) throw new IllegalArgumentException("累计裁决数量超过购买数量");
        UUID listingId = (UUID) row[3];
        long unitPriceFen = (Long) row[4];
        long amountFen = refundAmountFen(unitPriceFen, approvedQuantity);
        long paid = (Long) row[7];
        if (amountFen > paid) throw new IllegalArgumentException("退款超过实付金额");
        UUID paymentId = (UUID) row[6];
        if (paymentId == null) throw new IllegalStateException("没有成功支付");
        Instant now = databaseNow();
        UUID returnId = UUID.nameUUIDFromBytes((caseId + "|" + decision.name()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID confirmer = authority.kind() == ProofAuthority.Kind.PROVIDER ? null : authority.actorId();
        jdbc.update("INSERT INTO return_case (id,dispute_case_id,order_id,listing_id,payment_order_id,unit_price_fen,status,proof_type,proof_reference,confirmed_by,resolution_type,approved_quantity,deadline,created_at,updated_at) "
                + "VALUES (?,?,?,?,?,?,'CONFIRMED',?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE proof_type=VALUES(proof_type),proof_reference=VALUES(proof_reference),confirmed_by=VALUES(confirmed_by),resolution_type=VALUES(resolution_type),updated_at=VALUES(updated_at)",
            returnId.toString(), caseId.toString(), orderId.toString(), listingId.toString(), paymentId.toString(), unitPriceFen,
            proofType.name(), proofReference, confirmer == null ? null : confirmer.toString(), decision.name(), approvedQuantity, TimestampValue.of(now.plusSeconds(14 * 86400L)), TimestampValue.of(now), TimestampValue.of(now));
        jdbc.update("UPDATE dispute_case SET decision=?,approved_quantity=?,proof_type=?,proof_reference=?,status='RESOLVED',resolved_at=?,version=version+1,updated_at=? WHERE id=? AND status IN ('OPEN','SELLER_RESPONDED','UNDER_REVIEW','ESCALATED')",
            decision.name(), approvedQuantity, proofType.name(), proofReference, TimestampValue.of(now), TimestampValue.of(now), caseId.toString());
        return new CaseFacts(caseId, orderId, listingId, (Integer) row[5], approvedQuantity, unitPriceFen, amountFen, null, "REQUESTED");
    }

    private Resolution finish(CaseFacts facts, DisputeDecision decision, RefundService.RefundResult refund) {
        Instant now = databaseNow();
        jdbc.update("UPDATE return_case SET refund_id=?,refund_status=?,resolution_type=?,status=CASE WHEN ?='SUCCEEDED' THEN 'CONFIRMED' WHEN ?='FAILED' THEN 'ESCALATED' ELSE 'AWAITING_PROOF' END,refunded_at=CASE WHEN ?='SUCCEEDED' THEN ? ELSE refunded_at END,updated_at=? WHERE dispute_case_id=?",
            refund.refundId().toString(), refund.status(), decision.name(), refund.status(), refund.status(), refund.status(), TimestampValue.of(now), TimestampValue.of(now), facts.caseId().toString());
        return new Resolution(facts.caseId(), refund.refundId(), refund.status(), facts.amountFen(), false);
    }

    /** 退款回调/对账成功后的短事务收敛；库存唯一业务键使重复解析安全。 */
    public Resolution reconcileSuccessfulRefund(UUID refundId) {
        Objects.requireNonNull(refundId, "退款ID不能为空");
        ReturnFacts facts = transactions.execute(status -> {
            // prepare 已提交而 finish 尚未提交时，依据不可变幂等键把退款重新关联回退回案件。
            jdbc.update("UPDATE return_case r JOIN refund_order f ON f.id=? AND r.refund_id IS NULL "
                    + "AND f.order_id=r.order_id AND (f.idempotency_key=CONCAT('dispute-return-',r.dispute_case_id) "
                    + "OR f.idempotency_key=CONCAT('dispute-hard-refund-',r.dispute_case_id)) "
                    + "SET r.refund_id=f.id,r.refund_status=f.status,r.updated_at=CURRENT_TIMESTAMP(6)", refundId.toString());
            return jdbc.query("SELECT r.dispute_case_id,r.order_id,r.listing_id,r.approved_quantity,r.resolution_type,r.quarantined_at,f.status,f.amount_fen,o.quantity "
                + "FROM return_case r JOIN refund_order f ON f.id=r.refund_id JOIN trade_order o ON o.id=r.order_id WHERE f.id=? FOR UPDATE",
            rs -> rs.next() ? new ReturnFacts(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), UUID.fromString(rs.getString(3)),
                rs.getInt(4), rs.getString(5), rs.getTimestamp(6) != null, rs.getString(7), rs.getLong(8), rs.getInt(9)) : null, refundId.toString());
        });
        if (facts == null) return null;
        if (!"SUCCEEDED".equals(facts.refundStatus())) {
            if ("FAILED".equals(facts.refundStatus())) escalateFailedRefund(facts.disputeCaseId(), refundId);
            return new Resolution(facts.disputeCaseId(), refundId, facts.refundStatus(), facts.amountFen(), facts.quarantined());
        }
        boolean quarantined = facts.quarantined();
        if (!quarantined && "RETURN_AND_REFUND".equals(facts.resolutionType())) {
            if (!inventory.quarantine(facts.listingId(), facts.approvedQuantity(), "return-quarantine-" + facts.disputeCaseId()))
                throw new IllegalStateException("退回隔离库存失败，等待重试");
            quarantined = true;
        }
        final boolean doneQuarantine = quarantined;
        return transactions.execute(status -> {
            Instant now = databaseNow();
            jdbc.update("UPDATE return_case SET refund_status='SUCCEEDED',status='CONFIRMED',refunded_at=COALESCE(refunded_at,?),quarantined_at=CASE WHEN ? THEN COALESCE(quarantined_at,?) ELSE quarantined_at END,updated_at=? WHERE refund_id=? AND refund_status IN ('REQUESTED','PROCESSING','UNKNOWN','SUCCEEDED','FAILED')",
                TimestampValue.of(now), doneQuarantine, TimestampValue.of(now), TimestampValue.of(now), refundId.toString());
            Integer refundedQuantity = jdbc.queryForObject("SELECT COALESCE(SUM(approved_quantity),0) FROM return_case WHERE order_id=? AND refund_status='SUCCEEDED'", Integer.class, facts.orderId().toString());
            String target = refundedQuantity != null && refundedQuantity >= facts.purchasedQuantity() ? "REFUNDED" : "AFTERSALE_WINDOW";
            jdbc.update("UPDATE trade_order SET status=?,version=version+1,updated_at=? WHERE id=? AND status IN ('DISPUTED','AFTERSALE_WINDOW','REFUNDING_CANCEL')",
                target, TimestampValue.of(now), facts.orderId().toString());
            jdbc.update("UPDATE dispute_case SET status='RESOLVED',updated_at=? WHERE id=? AND status='ESCALATED'", TimestampValue.of(now), facts.disputeCaseId().toString());
            return new Resolution(facts.disputeCaseId(), refundId, "SUCCEEDED", facts.amountFen(), doneQuarantine);
        });
    }

    private Resolution reject(UUID caseId) {
        int changed = jdbc.update("UPDATE dispute_case SET status='REJECTED',decision='REJECT',approved_quantity=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('OPEN','SELLER_RESPONDED','UNDER_REVIEW','ESCALATED')", caseId.toString());
        if (changed != 1) throw new IllegalStateException("争议已经裁决");
        return new Resolution(caseId, null, "REJECTED", 0, false);
    }

    private Resolution queryResolution(CaseFacts facts) {
        var row = jdbc.query("SELECT refund_id,refund_status,quarantined_at FROM return_case WHERE dispute_case_id=?",
            rs -> rs.next() ? new Object[] { rs.getString(1) == null ? null : UUID.fromString(rs.getString(1)), rs.getString(2), rs.getTimestamp(3) != null } : null, facts.caseId().toString());
        return row == null ? new Resolution(facts.caseId(), null, "REQUESTED", facts.amountFen(), false)
            : new Resolution(facts.caseId(), (UUID) row[0], (String) row[1], facts.amountFen(), (Boolean) row[2]);
    }

    private void escalateFailedRefund(UUID caseId, UUID refundId) {
        transactions.execute(status -> {
            jdbc.update("UPDATE return_case SET refund_id=COALESCE(refund_id,?),refund_status='FAILED',status='ESCALATED',updated_at=CURRENT_TIMESTAMP(6) WHERE dispute_case_id=? AND (refund_id IS NULL OR refund_id=?)",
                refundId.toString(), caseId.toString(), refundId.toString());
            jdbc.update("UPDATE dispute_case SET status='ESCALATED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('RESOLVED','OPEN','SELLER_RESPONDED','UNDER_REVIEW')",
                caseId.toString());
            return null;
        });
    }

    private Instant databaseNow() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", java.sql.Timestamp.class).toInstant(); }
    public record Resolution(UUID disputeCaseId, UUID refundId, String refundStatus, long amountFen, boolean quarantined) {}
    public record ResolutionRequest(UUID disputeCaseId, DisputeDecision decision, int approvedQuantity,
                                    ReturnProofType proofType, String proofReference, ProofAuthority authority) {
        public ResolutionRequest(UUID disputeCaseId, DisputeDecision decision, int approvedQuantity,
                                 ReturnProofType proofType, String proofReference) {
            this(disputeCaseId, decision, approvedQuantity, proofType, proofReference, null);
        }
    }
    private record CaseFacts(UUID caseId, UUID orderId, UUID listingId, int purchasedQuantity, int approvedQuantity, long unitPriceFen,
                             long amountFen, UUID existingRefundId, String refundStatus) {}
    private record ReturnFacts(UUID disputeCaseId, UUID orderId, UUID listingId, int approvedQuantity, String resolutionType,
                               boolean quarantined, String refundStatus, long amountFen, int purchasedQuantity) {}
    private record HardFacts(UUID refundId, UUID orderId, long unitPriceFen, int approvedQuantity, String refundStatus, String resolutionType) {}
    private record PendingReturn(UUID caseId, UUID orderId, long unitPriceFen, int approvedQuantity, String resolutionType, String status) {}

    /** 硬期限必须复用已落库的卖家/分配管理员事实或已验证物流签收事实，不能只信 dispute_case.proof_type。 */
    public boolean isHardDeadlineProofAuthorized(UUID disputeCaseId, UUID orderId, int quantity,
                                                 String proofTypeValue, String proofReference, String provider) {
        if (proofTypeValue == null || proofReference == null || proofReference.isBlank()) return false;
        final ReturnProofType proofType;
        try { proofType = ReturnProofType.parse(proofTypeValue); }
        catch (RuntimeException invalid) { return false; }
        if (!proofType.isTrusted()) return false;
        try {
            if (proofType == ReturnProofType.PROVIDER_DELIVERED) {
                authorizeProof(ProofAuthority.provider(proofReference), proofType, proofReference,
                    orderId, quantity, null, null, provider);
                return true;
            }
            String actor = jdbc.query("SELECT CASE WHEN ?='SELLER_CONFIRMED' THEN o.seller_id ELSE c.assigned_admin_id END FROM dispute_case c JOIN trade_order o ON o.id=c.order_id WHERE c.id=? AND c.order_id=?",
                rs -> rs.next() ? rs.getString(1) : null, proofTypeValue, disputeCaseId.toString(), orderId.toString());
            if (actor == null || !actor.equals(proofReference)) return false;
            UUID actorId = UUID.fromString(actor);
            authorizeProof(proofType == ReturnProofType.SELLER_CONFIRMED ? ProofAuthority.seller(actorId) : ProofAuthority.admin(actorId),
                proofType, proofReference, orderId, quantity,
                proofType == ReturnProofType.SELLER_CONFIRMED ? actorId : null,
                proofType == ReturnProofType.ADMIN_CONFIRMED ? actorId : null, provider);
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private void authorizeProof(ProofAuthority authority, ReturnProofType proofType, String proofReference,
                                UUID orderId, int quantity, UUID sellerId, UUID assignedAdminId, String provider) {
        if (proofType == ReturnProofType.SELLER_CONFIRMED) {
            if (authority.kind() != ProofAuthority.Kind.SELLER || !sellerId.equals(authority.actorId()))
                throw new IllegalStateException("卖家证明主体未授权");
            return;
        }
        if (proofType == ReturnProofType.ADMIN_CONFIRMED) {
            if (authority.kind() != ProofAuthority.Kind.ADMIN || assignedAdminId == null || !assignedAdminId.equals(authority.actorId()))
                throw new IllegalStateException("管理员证明主体未分配");
            return;
        }
        if (proofType == ReturnProofType.PROVIDER_DELIVERED) {
            if (authority.kind() != ProofAuthority.Kind.PROVIDER || !proofReference.equals(authority.attestationReference()))
                throw new IllegalStateException("物流证明引用未验签");
            Integer verified = jdbc.queryForObject("SELECT COUNT(*) FROM return_proof_attestation WHERE proof_reference=? AND order_id=? AND provider=? AND status='DELIVERED' AND delivered_quantity>=? AND verified_at<=CURRENT_TIMESTAMP(6)",
                Integer.class, proofReference, orderId.toString(), provider, quantity);
            if (verified == null || verified != 1) throw new IllegalStateException("物流签收事实不存在或数量不足");
            return;
        }
        throw new IllegalStateException("证明来源不可信");
    }
    private static final class TimestampValue {
        private TimestampValue() {}
        static java.sql.Timestamp of(Instant value) { return java.sql.Timestamp.from(value); }
    }

    private record SellerReturnFacts(UUID orderId, DisputeDecision decision, Integer approvedQuantity, UUID sellerId) {}
    public static class NotFoundException extends RuntimeException {}
    public static class ForbiddenException extends RuntimeException {}
}
