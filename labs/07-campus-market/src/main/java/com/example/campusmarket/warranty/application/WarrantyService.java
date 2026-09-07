package com.example.campusmarket.warranty.application;

import com.example.campusmarket.dispute.application.EvidenceStorage;
import com.example.campusmarket.catalog.application.InventoryPort;
import com.example.campusmarket.payment.application.RefundService;
import com.example.campusmarket.order.domain.OrderStatus;
import com.example.campusmarket.order.domain.TradeOrder;
import com.example.campusmarket.catalog.domain.WarrantyTerm;
import com.example.campusmarket.shared.Money;
import com.example.campusmarket.warranty.domain.WarrantyCase;
import com.example.campusmarket.warranty.domain.WarrantyDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;

/** 卖家延长质保用例；案件在结算后独立流转，不回写订单终态。 */
@Service
@Profile("!test")
public final class WarrantyService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final RefundService refunds;
    private final EvidenceStorage evidence;
    private final InventoryPort inventory;

    public WarrantyService(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager tx,
                           ObjectMapper mapper, RefundService refunds, EvidenceStorage evidence, InventoryPort inventory) {
        this.jdbc=Objects.requireNonNull(jdbc); this.transactions=new TransactionTemplate(Objects.requireNonNull(tx));
        this.mapper=Objects.requireNonNull(mapper); this.refunds=Objects.requireNonNull(refunds); this.evidence=Objects.requireNonNull(evidence); this.inventory=Objects.requireNonNull(inventory);
    }

    public Result openWarrantyCase(UUID orderId, int quantity, String reason, String idempotencyKey, UUID buyerId) {
        return openWarrantyCase(orderId, quantity, reason, idempotencyKey, buyerId, null);
    }
    /** 领域/批处理调用的便捷入口；HTTP 调用必须传入已认证买家。 */
    public Result openWarrantyCase(UUID orderId, int quantity, String reason, String idempotencyKey) {
        UUID buyer = jdbc.queryForObject("SELECT buyer_id FROM trade_order WHERE id=?", (rs,n)->UUID.fromString(rs.getString(1)), orderId.toString());
        return openWarrantyCase(orderId, quantity, reason, idempotencyKey, buyer, null);
    }
    public Result openWarrantyCase(UUID orderId, int quantity, String reason, String idempotencyKey, UUID buyerId, byte[] request) {
        if (idempotencyKey==null||idempotencyKey.isBlank()) throw new IllegalArgumentException("幂等键不能为空");
        byte[] requestHash = digest(orderId+"|"+quantity+"|"+String.valueOf(reason)+"|"+idempotencyKey+"|"+Base64.getEncoder().encodeToString(request==null?new byte[0]:request));
        return transactions.execute(s -> {
            JdbcTemplate j=jdbc;
            OrderFacts row=j.query("SELECT id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,warranty_days,warranty_scope_snapshot,manufacturer_warranty_proof_snapshot,manufacturer_warranty_expires_at,status,created_at,t0 FROM trade_order WHERE id=? FOR UPDATE",
                rs -> rs.next()?new OrderFacts(UUID.fromString(rs.getString(1)),UUID.fromString(rs.getString(2)),UUID.fromString(rs.getString(3)),UUID.fromString(rs.getString(4)),rs.getString(5),rs.getString(6),rs.getLong(7),rs.getInt(8),(Integer)rs.getObject(9),rs.getString(10),rs.getString(11),ts(rs.getTimestamp(12)),OrderStatus.valueOf(rs.getString(13)),ts(rs.getTimestamp(14)),ts(rs.getTimestamp(15))):null,orderId.toString());
            if(row==null||buyerId==null||!buyerId.equals(row.buyerId())) throw new NotFoundException();
            Existing existing=j.query("SELECT id,order_id,status,buyer_id,request_hash FROM warranty_case WHERE order_id=? AND idempotency_key=? FOR UPDATE", rs -> rs.next() ? new Existing(UUID.fromString(rs.getString(1)),rs.getString(2),rs.getString(3),UUID.fromString(rs.getString(4)),rs.getBytes(5)):null, orderId.toString(),idempotencyKey);
            if (existing!=null) {
                if (!buyerId.equals(existing.buyerId()) || existing.requestHash()!=null && !MessageDigest.isEqual(existing.requestHash(), requestHash)) throw new IdempotencyConflictException();
                return new Result(existing.id(),existing.status());
            }
            Instant now=dbNow();
            TradeOrder order=TradeOrder.reconstitute(row.id(),row.buyerId(),row.sellerId(),new TradeOrder.ListingSnapshot(row.listingId(),row.sellerId(),row.title(),row.description(),"snapshot",Money.ofFen(row.unitPriceFen()),row.warrantyDays()==null?WarrantyTerm.none():WarrantyTerm.sellerWarrantyDays(row.warrantyDays()),row.scope()==null?"SELLER_NON_HUMAN_FUNCTIONAL_FAILURE":row.scope(),row.manufacturerProof(),row.manufacturerExpires()),row.quantity(),row.t0()==null?row.createdAt():row.t0(),row.status());
            WarrantyCase file=WarrantyCase.open(order,quantity,parseReason(reason),now);
            j.update("INSERT INTO warranty_case(id,order_id,idempotency_key,request_hash,buyer_id,seller_id,warranty_days,warranty_scope_snapshot,manufacturer_warranty_proof_snapshot,manufacturer_warranty_expires_at,disputed_quantity,reason,status,seller_deadline,version,opened_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'OPEN',?,0,?,?,?)",
                file.id().toString(),orderId.toString(),idempotencyKey,requestHash,buyerId.toString(),row.sellerId().toString(),file.warrantyDays(),file.warrantyScopeSnapshot(),file.manufacturerWarrantyProofSnapshot(),ts(file.manufacturerWarrantyExpiresAt()),quantity,file.reason().name(),ts(file.sellerDeadline()),ts(now),ts(now),ts(now));
            j.update("INSERT INTO warranty_deadline_claim(id,warranty_case_id,deadline_type,due_at,status,created_at,updated_at) VALUES (?,?, 'SELLER_RESPONSE',?,'NEW',?,?), (?,?, 'ADMIN_SLA',?,'NEW',?,?), (?,?, 'HARD_DEADLINE',?,'NEW',?,?)",
                UUID.randomUUID().toString(),file.id().toString(),ts(file.sellerDeadline()),ts(now),ts(now), UUID.randomUUID().toString(),file.id().toString(),ts(file.sellerDeadline().plusSeconds(7*86400L)),ts(now),ts(now), UUID.randomUUID().toString(),file.id().toString(),ts(file.sellerDeadline().plusSeconds(14*86400L)),ts(now),ts(now));
            auditAndOutbox(file.id(), buyerId, "WARRANTY_CASE_CREATED", "OPEN", 1L);
            return new Result(file.id(),file.status().name());
        });
    }

    public Result decide(UUID caseId, UUID adminId, WarrantyDecision decision, long compensationFen, String evidenceId) {
        return decide(caseId, adminId, decision, compensationFen, evidenceId, "legacy-"+caseId+"-"+decision);
    }
    public Result decide(UUID caseId, UUID adminId, WarrantyDecision decision, long compensationFen, String evidenceId, String idempotencyKey) {
        if (idempotencyKey==null||idempotencyKey.isBlank()) throw new IllegalArgumentException("幂等键不能为空");
        final long requestedCompensationFen = compensationFen;
        final byte[] requestHash = digest(caseId+"|"+adminId+"|"+decision+"|"+compensationFen+"|"+String.valueOf(evidenceId)+"|"+idempotencyKey);
        DecisionResult result=transactions.execute(s -> {
            long approvedCompensationFen = requestedCompensationFen;
            UUID hintedOrder=jdbc.queryForObject("SELECT order_id FROM warranty_case WHERE id=?",(rs,n)->UUID.fromString(rs.getString(1)),caseId.toString());
            jdbc.query("SELECT id FROM trade_order WHERE id=? FOR UPDATE",rs->{if(!rs.next())throw new NotFoundException();return rs.getString(1);},hintedOrder.toString());
            CaseRow row=jdbc.query("SELECT id,order_id,buyer_id,seller_id,disputed_quantity,status,assigned_admin_id,version,decision_idempotency_key,decision_request_hash,reason FROM warranty_case WHERE id=? FOR UPDATE",rs->rs.next()?new CaseRow(UUID.fromString(rs.getString(1)),UUID.fromString(rs.getString(2)),UUID.fromString(rs.getString(3)),UUID.fromString(rs.getString(4)),rs.getInt(5),rs.getString(6),uuid(rs.getString(7)),rs.getLong(8),rs.getString(9),rs.getBytes(10),WarrantyCase.Reason.valueOf(rs.getString(11))):null,caseId.toString());
            if(row==null||row.assignedAdmin()!=null&&!row.assignedAdmin().equals(adminId)) throw new NotFoundException();
            if(decision==null) throw new IllegalArgumentException("裁定不能为空");
            if (row.decisionKey()!=null) {
                if (row.decisionKey().equals(idempotencyKey) && row.decisionHash()!=null && MessageDigest.isEqual(row.decisionHash(), requestHash)) return new DecisionResult(row.orderId(), decision, 0L);
                throw new IdempotencyConflictException();
            }
            if (row.status().equals("RESOLVED") || row.status().equals("REJECTED")) throw new IllegalStateException("质保案件已经裁定");
            if (row.reason().isExclusion() && decision != WarrantyDecision.REJECT) throw new IllegalStateException("排除原因不得判定卖家承担质保");
            if(decision!=WarrantyDecision.REJECT && (evidenceId==null||evidenceId.isBlank())) throw new IllegalArgumentException("卖家承担义务必须有可信证据");
            if (decision != WarrantyDecision.REJECT) {
                UUID evidenceUuid;
                try { evidenceUuid=UUID.fromString(evidenceId); } catch (RuntimeException ex) { throw new IllegalArgumentException("证据不存在",ex); }
                Integer evidenceCount=jdbc.queryForObject("SELECT COUNT(*) FROM dispute_evidence e JOIN warranty_case w ON w.id=e.warranty_case_id WHERE e.id=? AND e.warranty_case_id=? AND e.case_type='WARRANTY' AND (e.submitted_by=w.buyer_id OR e.submitted_by=w.seller_id) AND e.verification_status='VERIFIED' AND (? <> 'RETURN_AND_REFUND' OR e.purpose='RETURN_PROOF') AND (? <> 'REPAIR_COMPENSATION' OR e.purpose IN ('REPAIR_QUOTE','INVOICE'))",Integer.class,evidenceUuid.toString(),caseId.toString(),decision.name(),decision.name());
                if(evidenceCount==null||evidenceCount!=1) throw new IllegalArgumentException("证据不存在");
                // Payment/refund services use one canonical successful attempt:
                // the newest payment fact (created_at,id tie-break). Do not
                // combine the newest paid amount with refunds from older
                // attempts, which can manufacture a false exhausted balance.
                PaymentFacts payment = jdbc.query("SELECT paid_amount_fen,successful_refund_fen,reserved_refund_fen FROM payment_order WHERE order_id=? AND status='SUCCEEDED' ORDER BY created_at DESC,id DESC LIMIT 1 FOR UPDATE",
                    rs -> rs.next() ? new PaymentFacts(rs.getLong(1),rs.getLong(2),rs.getLong(3)) : null, row.orderId().toString());
                if (payment == null) throw new IllegalStateException("订单尚未支付成功");
                if (decision == WarrantyDecision.REPAIR_COMPENSATION) {
                    approvedCompensationFen = WarrantyCase.approvedCompensation(Money.ofFen(requestedCompensationFen), evidenceId,
                        Money.ofFen(payment.paid()), Money.ofFen(payment.successful()), Money.ofFen(payment.reserved())).fen();
                } else {
                    long gross;
                    try { gross=Math.multiplyExact(jdbc.queryForObject("SELECT unit_price_fen FROM trade_order WHERE id=?",Long.class,row.orderId().toString()), (long)row.quantity()); }
                    catch (ArithmeticException ex) { throw new IllegalArgumentException("退款金额溢出",ex); }
                    approvedCompensationFen=Math.min(gross, Math.subtractExact(Math.subtractExact(payment.paid(),payment.successful()),payment.reserved()));
                    if (approvedCompensationFen<=0) throw new IllegalStateException("退款额度已用尽");
                }
            }
            int changed=jdbc.update("UPDATE warranty_case SET status=?,decision=?,compensation_amount_fen=?,assigned_admin_id=?,decision_idempotency_key=?,decision_request_hash=?,version=version+1,closed_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('OPEN','SELLER_RESPONDED','UNDER_REVIEW','ESCALATED') AND version=?",
                decision==WarrantyDecision.REJECT?"REJECTED":"RESOLVED",decision.name(),decision==WarrantyDecision.REJECT?0L:approvedCompensationFen,adminId.toString(),idempotencyKey,requestHash,caseId.toString(),row.version());
            if(changed!=1) throw new IllegalStateException("质保裁定状态冲突");
            if(decision!=WarrantyDecision.REJECT) {
                if(approvedCompensationFen<=0) throw new IllegalArgumentException("补偿金额必须为正数");
                UUID obligation=UUID.nameUUIDFromBytes(("warranty-obligation:"+caseId).getBytes(StandardCharsets.UTF_8));
                String key="warranty:"+caseId;
                jdbc.update("INSERT INTO seller_obligation(id,warranty_case_id,seller_id,obligation_business_key,obligation_amount_fen,funding_deadline,future_settlement_deduction_key,restriction_status,status,version,created_at,updated_at) VALUES (?,?,?,?,?,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 72 HOUR),NULL,'RESTRICTED','AWAITING_FUNDING',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id",
                    obligation.toString(),caseId.toString(),row.sellerId().toString(),key,approvedCompensationFen);
                jdbc.update("INSERT INTO seller_account_restriction(seller_id,restriction_type,source_obligation_id,status,created_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE status='ACTIVE',cleared_at=NULL",
                    row.sellerId().toString(),"PUBLISH",obligation.toString());
                jdbc.update("INSERT INTO seller_account_restriction(seller_id,restriction_type,source_obligation_id,status,created_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE status='ACTIVE',cleared_at=NULL",
                    row.sellerId().toString(),"WITHDRAW",obligation.toString());
                recordObligationTransition(obligation, row.sellerId(), "SELLER_OBLIGATION_CREATED", row.version()+1,
                    "{\"amountFen\":"+approvedCompensationFen+",\"caseId\":\""+caseId+"\"}");
                recordObligationTransition(obligation, row.sellerId(), "SELLER_RESTRICTION_ACTIVATED", row.version()+1,
                    "{\"restrictionTypes\":[\"PUBLISH\",\"WITHDRAW\"]}");
                if (decision == WarrantyDecision.RETURN_AND_REFUND) {
                    UUID listingId=jdbc.queryForObject("SELECT listing_id FROM trade_order WHERE id=?",(rs,n)->UUID.fromString(rs.getString(1)),row.orderId().toString());
                    if (!inventory.quarantine(listingId, row.quantity(), "warranty-return-quarantine-"+caseId)) throw new IllegalStateException("退回商品隔离失败");
                    jdbc.update("INSERT INTO return_case(id,dispute_case_id,warranty_case_id,status,proof_type,proof_reference,confirmed_by,approved_quantity,deadline,created_at,updated_at) VALUES (?,?,?,'CONFIRMED','RETURN_PROOF',?,?,?,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 14 DAY),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE status='CONFIRMED',confirmed_by=VALUES(confirmed_by),updated_at=CURRENT_TIMESTAMP(6)",UUID.nameUUIDFromBytes(("warranty-return:"+caseId).getBytes(StandardCharsets.UTF_8)).toString(),null,caseId.toString(),evidenceId,adminId.toString(),row.quantity());
                }
            }
            auditAndOutbox(caseId, adminId, "WARRANTY_DECIDED", decision.name(), row.version()+1);
            return new DecisionResult(row.orderId(),decision,approvedCompensationFen);
        });
        return new Result(caseId,result==null||result.decision()==WarrantyDecision.REJECT?"REJECTED":"RESOLVED");
    }

    public Result respond(UUID caseId, UUID sellerId) {
        return transactions.execute(s -> {
            UUID order = jdbc.queryForObject("SELECT order_id FROM warranty_case WHERE id=?", (rs,n)->UUID.fromString(rs.getString(1)), caseId.toString());
            jdbc.query("SELECT id FROM trade_order WHERE id=? AND seller_id=? FOR UPDATE", rs->{if(!rs.next())throw new NotFoundException();return rs.getString(1);}, order.toString(), sellerId.toString());
            if (jdbc.update("UPDATE warranty_case SET status='SELLER_RESPONDED',version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND seller_id=? AND status='OPEN'", caseId.toString(), sellerId.toString()) != 1) throw new IllegalStateException("质保状态冲突");
            return new Result(caseId,"SELLER_RESPONDED");
        });
    }

    public Result assign(UUID caseId, UUID adminId) {
        return transactions.execute(s -> {
            if (jdbc.update("UPDATE warranty_case SET assigned_admin_id=?,status=CASE WHEN status='OPEN' THEN 'UNDER_REVIEW' ELSE status END,admin_deadline=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 7 DAY),hard_deadline=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 14 DAY),version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('OPEN','SELLER_RESPONDED','UNDER_REVIEW')", adminId.toString(), caseId.toString()) != 1) throw new NotFoundException();
            jdbc.update("UPDATE warranty_deadline_claim SET due_at=CASE deadline_type WHEN 'ADMIN_SLA' THEN DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 7 DAY) WHEN 'HARD_DEADLINE' THEN DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 14 DAY) ELSE due_at END,status=CASE WHEN deadline_type IN ('ADMIN_SLA','HARD_DEADLINE') THEN 'NEW' ELSE status END,updated_at=CURRENT_TIMESTAMP(6) WHERE warranty_case_id=? AND deadline_type IN ('ADMIN_SLA','HARD_DEADLINE')", caseId.toString());
            return new Result(caseId,"UNDER_REVIEW");
        });
    }

    public EvidenceStorage.EvidenceRecord attachEvidence(UUID caseId, UUID actorId, String filename, String type, java.io.InputStream input) {
        throw new IllegalArgumentException("质保证据必须声明用途");
    }
    public EvidenceStorage.EvidenceRecord attachEvidence(UUID caseId, UUID actorId, String filename, String type, String purpose, java.io.InputStream input) {
        if (purpose==null||purpose.isBlank()||(!purpose.equals("REPAIR_QUOTE")&&!purpose.equals("INVOICE")&&!purpose.equals("RETURN_PROOF")))
            throw new IllegalArgumentException("证据用途无效");
        return evidence.attach("WARRANTY",caseId,actorId,filename,type,purpose,input);
    }
    public EvidenceStorage.OpenedEvidence openEvidence(UUID caseId, UUID evidenceId, UUID actorId) { return evidence.open("WARRANTY",caseId,evidenceId,actorId); }
    public CaseView find(UUID caseId) { return jdbc.query("SELECT id,order_id,buyer_id,seller_id,status,decision,compensation_amount_fen,seller_deadline,admin_deadline,hard_deadline FROM warranty_case WHERE id=?",rs->rs.next()?new CaseView(UUID.fromString(rs.getString(1)),UUID.fromString(rs.getString(2)),UUID.fromString(rs.getString(3)),UUID.fromString(rs.getString(4)),rs.getString(5),rs.getString(6),rs.getLong(7),ts(rs.getTimestamp(8)),ts(rs.getTimestamp(9)),ts(rs.getTimestamp(10))):null,caseId.toString()); }
    public CaseView find(UUID caseId, UUID actorId) {
        CaseView view=find(caseId);
        Integer admin = actorId==null?0:jdbc.queryForObject("SELECT COUNT(*) FROM warranty_case WHERE id=? AND assigned_admin_id=?",Integer.class,caseId.toString(),actorId.toString());
        if(view==null || actorId==null || !(actorId.equals(view.buyerId())||actorId.equals(view.sellerId())||Integer.valueOf(1).equals(admin))) throw new NotFoundException();
        return view;
    }
    private WarrantyCase.Reason parseReason(String value) { try{return WarrantyCase.Reason.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));}catch(Exception e){throw new IllegalArgumentException("质保理由无效",e);} }
    private Instant dbNow(){return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class).toInstant();} private static Timestamp ts(Instant i){return i==null?null:Timestamp.from(i);} private static Instant ts(Timestamp t){return t==null?null:t.toInstant();} private static UUID uuid(String s){return s==null?null:UUID.fromString(s);}
    private void auditAndOutbox(UUID caseId, UUID actor, String type, String decision, long version) {
        UUID event=UUID.nameUUIDFromBytes((type+":"+caseId+":"+version).getBytes(StandardCharsets.UTF_8));
        String payload="{\"caseId\":\""+caseId+"\",\"decision\":\""+decision+"\"}";
        jdbc.update("INSERT INTO audit_event(id,actor_id,action,resource_type,resource_id,result,details,occurred_at) VALUES (?,?,?,'WARRANTY_CASE',?,'SUCCESS',CAST(? AS JSON),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id",event.toString(),actor.toString(),type,caseId.toString(),payload);
        jdbc.update("INSERT INTO integration_outbox(id,event_id,event_type,aggregate_id,aggregate_version,schema_version,occurred_at,payload,status,attempt_count,available_at,created_at) VALUES (?,?,?, ?,?,1,CURRENT_TIMESTAMP(6),CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id",event.toString(),event.toString(),type,caseId.toString(),version,payload);
    }
    private void recordObligationTransition(UUID obligation, UUID actor, String type, long version, String payload) {
        UUID event=UUID.nameUUIDFromBytes((type+":"+obligation+":"+version).getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO audit_event(id,actor_id,action,resource_type,resource_id,result,details,occurred_at) VALUES (?,?,?,'SELLER_OBLIGATION',?,'SUCCESS',CAST(? AS JSON),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id",event.toString(),actor.toString(),type,obligation.toString(),payload);
        jdbc.update("INSERT INTO integration_outbox(id,event_id,event_type,aggregate_id,aggregate_version,schema_version,occurred_at,payload,status,attempt_count,available_at,created_at) VALUES (?,?,?, ?,?,1,CURRENT_TIMESTAMP(6),CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id",event.toString(),event.toString(),type,obligation.toString(),version,payload);
    }
    private static byte[] digest(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException(e);}}
    private record Existing(UUID id,String orderId,String status,UUID buyerId,byte[] requestHash){} private record OrderFacts(UUID id,UUID buyerId,UUID sellerId,UUID listingId,String title,String description,long unitPriceFen,int quantity,Integer warrantyDays,String scope,String manufacturerProof,Instant manufacturerExpires,OrderStatus status,Instant createdAt,Instant t0){} private record CaseRow(UUID id,UUID orderId,UUID buyerId,UUID sellerId,int quantity,String status,UUID assignedAdmin,long version,String decisionKey,byte[] decisionHash,WarrantyCase.Reason reason){} private record DecisionResult(UUID orderId,WarrantyDecision decision,long amount){} private record PaymentFacts(long paid,long successful,long reserved){}
    public record Result(UUID caseId,String status){} public record CaseView(UUID id,UUID orderId,UUID buyerId,UUID sellerId,String status,String decision,long compensationAmountFen,Instant sellerDeadline,Instant adminDeadline,Instant hardDeadline){}
    public static class NotFoundException extends RuntimeException{}
    public static class IdempotencyConflictException extends RuntimeException{}
}
