package com.example.campusmarket.warranty.application;

import com.example.campusmarket.payment.application.RefundService;
import com.example.campusmarket.shared.Money;
import com.example.campusmarket.shared.infrastructure.SellerBalanceLockRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 义务筹资、未来结算抵扣与限制解除；所有累计更新均由 MySQL 条件更新保证幂等。 */
@Service
@Profile("!test")
public final class SellerObligationService {
    private final JdbcTemplate jdbc; private final TransactionTemplate transactions; private final RefundService refunds; private final SellerBalanceLockRepository sellerLocks;
    /** 兼容仍直接提供加锁前依赖的调用方。 */
    public SellerObligationService(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager tx, RefundService refunds) {
        this(jdbc, tx, refunds, jdbc == null ? null : new SellerBalanceLockRepository(jdbc));
    }

    @Autowired
    public SellerObligationService(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager tx, RefundService refunds,
                                   SellerBalanceLockRepository sellerLocks) { this.jdbc=Objects.requireNonNull(jdbc); this.transactions=new TransactionTemplate(Objects.requireNonNull(tx)); this.refunds=Objects.requireNonNull(refunds); this.sellerLocks=Objects.requireNonNull(sellerLocks); }

    public FundingResult fundObligation(UUID obligationId, UUID sellerId, Money amount) {
        return fundObligation(obligationId, sellerId, amount, "legacy-"+obligationId+"-"+amount.fen());
    }
    public FundingResult fundObligation(UUID obligationId, UUID sellerId, Money amount, String idempotencyKey) {
        Objects.requireNonNull(amount,"筹资金额不能为空");
        if (idempotencyKey==null||idempotencyKey.isBlank()) throw new IllegalArgumentException("幂等键不能为空");
        byte[] requestHash=digest(obligationId+"|"+sellerId+"|"+amount.fen()+"|"+idempotencyKey);
        FundingOutcome outcome=transactions.execute(s -> {
            Obligation row=jdbc.query("SELECT id,warranty_case_id,seller_id,obligation_amount_fen,funded_amount_fen,status,funding_deadline,version FROM seller_obligation WHERE id=? FOR UPDATE",rs->rs.next()?new Obligation(UUID.fromString(rs.getString(1)),UUID.fromString(rs.getString(2)),UUID.fromString(rs.getString(3)),rs.getLong(4),rs.getLong(5),rs.getString(6),rs.getTimestamp(7).toInstant(),rs.getLong(8)):null,obligationId.toString());
            if(row==null||!row.sellerId().equals(sellerId)) throw new NotFoundException();
            FundingCommand prior=jdbc.query("SELECT amount_fen,request_hash FROM seller_obligation_funding WHERE obligation_id=? AND idempotency_key=?",rs->rs.next()?new FundingCommand(rs.getLong(1),rs.getBytes(2)):null,obligationId.toString(),idempotencyKey);
            if(prior!=null){if(prior.amount()==amount.fen()&&MessageDigest.isEqual(prior.hash(),requestHash))return new FundingOutcome(new FundingResult(obligationId,row.caseId(),row.amount(),row.funded(),row.status()),false);throw new IdempotencyConflictException();}
            if(amount.fen()<=0) throw new IllegalArgumentException("筹资金额必须为正数");
            Instant now=dbNow(); if(!now.isBefore(row.deadline())&&row.funded()<row.amount()) { int changed=jdbc.update("UPDATE seller_obligation SET status='CANCELLED',restriction_status='RESTRICTED',version=version+1,updated_at=? WHERE id=? AND status IN ('AWAITING_FUNDING','PARTIALLY_FUNDED') AND funded_amount_fen<obligation_amount_fen",Timestamp.from(now),obligationId.toString()); if(changed==1) recordTransition(sellerId,"SELLER_OBLIGATION_EXPIRED",obligationId,row.version()+1,"{\"fundedAmountFen\":"+row.funded()+",\"obligationAmountFen\":"+row.amount()+"}"); return new FundingOutcome(new FundingResult(obligationId,row.caseId(),row.amount(),row.funded(),"CANCELLED"),true); }
            if(row.funded()==row.amount()) return new FundingOutcome(new FundingResult(obligationId,row.caseId(),row.amount(),row.funded(),"FUNDED"),false);
            long next; try{next=Math.addExact(row.funded(),amount.fen());}catch(ArithmeticException e){throw new IllegalArgumentException("筹资金额溢出",e);}
            if(next>row.amount()) throw new IllegalArgumentException("筹资金额超出义务");
            String state=next==row.amount()?"FUNDED":"PARTIALLY_FUNDED";
            if(jdbc.update("UPDATE seller_obligation SET funded_amount_fen=?,status=?,restriction_status=?,version=version+1,updated_at=? WHERE id=? AND seller_id=? AND funded_amount_fen=? AND status IN ('AWAITING_FUNDING','PARTIALLY_FUNDED')",next,state,next==row.amount()?"NONE":"RESTRICTED",Timestamp.from(now),obligationId.toString(),sellerId.toString(),row.funded())!=1) throw new ConcurrentFundingException();
            jdbc.update("INSERT INTO seller_obligation_funding(id,obligation_id,idempotency_key,request_hash,amount_fen,created_at) VALUES (?,?,?,?,?,?)",UUID.randomUUID().toString(),obligationId.toString(),idempotencyKey,requestHash,amount.fen(),Timestamp.from(now));
            recordTransition(sellerId, "SELLER_OBLIGATION_FUNDED", obligationId, row.version()+1, "{\"fundedAmountFen\":"+next+"}");
            if(next==row.amount()) { clearRestrictions(sellerId,obligationId,now,row.version()+1); refundOutbox(row.caseId(), obligationId, row.amount(), row.version()+1); }
            return new FundingOutcome(new FundingResult(obligationId,row.caseId(),row.amount(),next,state),false);
        });
        FundingResult result=outcome.result();
        if(outcome.expired()) throw new FundingExpiredException();
        if(result!=null&&"FUNDED".equals(result.status())) {
            UUID orderId=jdbc.queryForObject("SELECT order_id FROM warranty_case WHERE id=?",(rs,n)->UUID.fromString(rs.getString(1)),result.warrantyCaseId().toString());
            refunds.requestRefund(orderId,"warranty-refund-"+obligationId,Money.ofFen(result.obligationAmountFen()),"WARRANTY",result.warrantyCaseId());
        }
        return result;
    }

    /** 按最早到期义务抵扣一笔未来结算；(settlementId, obligationId) 唯一键提供幂等。 */
    public long deductFutureSettlement(UUID settlementId, UUID obligationId, Money amount) {
        Objects.requireNonNull(settlementId); Objects.requireNonNull(obligationId); Objects.requireNonNull(amount);
        if (amount.fen() <= 0) throw new IllegalArgumentException("抵扣金额必须为正数");
        UUID seller=findObligationSeller(obligationId);
        if (seller==null) throw new NotFoundException();
        long deducted = transactions.execute(s->{
            // 所有结算余额写入路径固定按卖家 → 订单 → 结算 → 义务/限制加锁。
            sellerLocks.lock(seller);
            UUID orderId=jdbc.query("SELECT order_id FROM settlement WHERE id=?",rs->rs.next()?UUID.fromString(rs.getString(1)):null,settlementId.toString());
            if (orderId==null) throw new IllegalStateException("结算不存在");
            OrderFacts order=jdbc.query("SELECT id,seller_id FROM trade_order WHERE id=? FOR UPDATE",rs->rs.next()?new OrderFacts(UUID.fromString(rs.getString(1)),UUID.fromString(rs.getString(2))):null,orderId.toString());
            SettlementFacts settlement=jdbc.query("SELECT order_id,status,net_settlement_fen FROM settlement WHERE id=? FOR UPDATE",rs->rs.next()?new SettlementFacts(UUID.fromString(rs.getString(1)),rs.getString(2),rs.getLong(3)):null,settlementId.toString());
            Obligation row=jdbc.query("SELECT id,warranty_case_id,seller_id,obligation_amount_fen,funded_amount_fen,status,version FROM seller_obligation WHERE id=? FOR UPDATE",rs->rs.next()?new Obligation(UUID.fromString(rs.getString(1)),UUID.fromString(rs.getString(2)),UUID.fromString(rs.getString(3)),rs.getLong(4),rs.getLong(5),rs.getString(6),null,rs.getLong(7)):null,obligationId.toString());
            if (order==null || settlement==null || row==null || !"SETTLED".equals(settlement.status())
                || !seller.equals(order.sellerId()) || !seller.equals(row.sellerId())
                || !orderId.equals(settlement.orderId())) throw new IllegalStateException("结算归属或状态无效");
            if (settlement.net() <= 0) return 0L;
            long remaining=row.amount()-row.funded(); if(remaining<=0) return 0L;
            long deduction=Math.min(Math.min(amount.fen(),remaining),settlement.net());
            int inserted=jdbc.update("INSERT INTO settlement_obligation_deduction(id,settlement_id,obligation_id,amount_fen,created_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id",UUID.randomUUID().toString(),settlementId.toString(),obligationId.toString(),deduction);
            if(inserted!=1) return 0L;
            long next=row.funded()+deduction;
            if(jdbc.update("UPDATE seller_obligation SET funded_amount_fen=?,status=?,restriction_status=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND funded_amount_fen=?",next,next==row.amount()?"FUNDED":"PARTIALLY_FUNDED",next==row.amount()?"NONE":"RESTRICTED",obligationId.toString(),row.funded())!=1) throw new ConcurrentFundingException();
            if(jdbc.update("UPDATE settlement SET net_settlement_fen=net_settlement_fen-? WHERE id=? AND net_settlement_fen>=?",deduction,settlementId.toString(),deduction)!=1) throw new ConcurrentFundingException();
            recordTransition(row.sellerId(), "SELLER_OBLIGATION_DEDUCTED", obligationId, row.version()+1, "{\"settlementId\":\""+settlementId+"\",\"amountFen\":"+deduction+"}");
            if(next==row.amount()) { clearRestrictions(row.sellerId(),obligationId,dbNow(),row.version()+1); refundOutbox(row.caseId(), obligationId, row.amount(), row.version()+1); }
            return deduction;
        });
        if (deducted > 0) {
            String status = jdbc.queryForObject("SELECT status FROM seller_obligation WHERE id=?", String.class, obligationId.toString());
            if ("FUNDED".equals(status)) {
                UUID orderId = jdbc.queryForObject("SELECT w.order_id FROM seller_obligation o JOIN warranty_case w ON w.id=o.warranty_case_id WHERE o.id=?", (rs,n)->UUID.fromString(rs.getString(1)), obligationId.toString());
                UUID caseId = jdbc.queryForObject("SELECT warranty_case_id FROM seller_obligation WHERE id=?", (rs,n)->UUID.fromString(rs.getString(1)), obligationId.toString());
                refunds.requestRefund(orderId, "warranty-refund-" + obligationId, Money.ofFen(jdbc.queryForObject("SELECT obligation_amount_fen FROM seller_obligation WHERE id=?", Long.class, obligationId.toString())), "WARRANTY", caseId);
            }
        }
        return deducted;
    }
    public boolean isRestricted(UUID sellerId,String type){ Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM seller_account_restriction WHERE seller_id=? AND restriction_type=? AND status='ACTIVE'",Integer.class,sellerId.toString(),type); return n!=null&&n>0; }
    public java.util.List<ObligationView> findForSeller(UUID sellerId) {
        return jdbc.query("SELECT id,warranty_case_id,obligation_amount_fen,funded_amount_fen,status,funding_deadline FROM seller_obligation WHERE seller_id=? ORDER BY funding_deadline,id",
            (rs,n)->new ObligationView(UUID.fromString(rs.getString(1)),UUID.fromString(rs.getString(2)),rs.getLong(3),rs.getLong(4),rs.getString(5),rs.getTimestamp(6).toInstant()),sellerId.toString());
    }
    private UUID findObligationSeller(UUID obligationId) {
        return jdbc.query("SELECT seller_id FROM seller_obligation WHERE id=?",rs->rs.next()?UUID.fromString(rs.getString(1)):null,obligationId.toString());
    }
    private void clearRestrictions(UUID seller,UUID obligation,Instant now,long version){
        var types=jdbc.query("SELECT restriction_type FROM seller_account_restriction WHERE seller_id=? AND source_obligation_id=? AND status='ACTIVE' FOR UPDATE",(rs,n)->rs.getString(1),seller.toString(),obligation.toString());
        jdbc.update("UPDATE seller_account_restriction SET status='CLEARED',cleared_at=? WHERE seller_id=? AND source_obligation_id=? AND status='ACTIVE'",Timestamp.from(now),seller.toString(),obligation.toString());
        for(String type:types) recordTransition(seller,"SELLER_RESTRICTION_CLEARED",obligation,version,"{\"restrictionType\":\""+type+"\"}");
    }
    private void refundOutbox(UUID caseId, UUID obligationId, long amount, long version) {
        UUID event=UUID.nameUUIDFromBytes(("warranty-refund:"+obligationId).getBytes(StandardCharsets.UTF_8));
        UUID order=jdbc.queryForObject("SELECT order_id FROM warranty_case WHERE id=?",(rs,n)->UUID.fromString(rs.getString(1)),caseId.toString());
        String payload="{\"orderId\":\""+order+"\",\"caseId\":\""+caseId+"\",\"obligationId\":\""+obligationId+"\",\"amountFen\":"+amount+"}";
        jdbc.update("INSERT INTO integration_outbox(id,event_id,event_type,aggregate_id,aggregate_version,schema_version,occurred_at,payload,status,attempt_count,available_at,created_at) VALUES (?,?, 'WARRANTY_REFUND_REQUESTED',?,?,1,CURRENT_TIMESTAMP(6),CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id",event.toString(),event.toString(),obligationId.toString(),version,payload);
    }
    private void recordTransition(UUID actor, String action, UUID obligation, long version, String details) {
        UUID event=UUID.nameUUIDFromBytes((action+":"+obligation+":"+version+":"+details).getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO audit_event(id,actor_id,action,resource_type,resource_id,result,details,occurred_at) VALUES (?,?,?,'SELLER_OBLIGATION',?,'SUCCESS',CAST(? AS JSON),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id",event.toString(),actor.toString(),action,obligation.toString(),details);
        jdbc.update("INSERT INTO integration_outbox(id,event_id,event_type,aggregate_id,aggregate_version,schema_version,occurred_at,payload,status,attempt_count,available_at,created_at) VALUES (?,?,?, ?,?,1,CURRENT_TIMESTAMP(6),CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id",event.toString(),event.toString(),action,obligation.toString(),version,details);
    }
    private Instant dbNow(){return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class).toInstant();}
    private static byte[] digest(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException(e);}}
    private record Obligation(UUID id,UUID caseId,UUID sellerId,long amount,long funded,String status,Instant deadline,long version,String fundingKey,byte[] fundingHash){ Obligation(UUID id,UUID caseId,UUID sellerId,long amount,long funded,String status,Instant deadline){this(id,caseId,sellerId,amount,funded,status,deadline,0L,null,null);} Obligation(UUID id,UUID caseId,UUID sellerId,long amount,long funded,String status,Instant deadline,long version){this(id,caseId,sellerId,amount,funded,status,deadline,version,null,null);} }
    private record FundingCommand(long amount,byte[] hash){}
    private record OrderFacts(UUID id,UUID sellerId){}
    private record SettlementFacts(UUID orderId,String status,long net){}
    public record FundingResult(UUID obligationId,UUID warrantyCaseId,long obligationAmountFen,long fundedAmountFen,String status){}
    private record FundingOutcome(FundingResult result, boolean expired){}
    public record ObligationView(UUID obligationId,UUID warrantyCaseId,long obligationAmountFen,long fundedAmountFen,String status,Instant fundingDeadline){}
    public static class NotFoundException extends RuntimeException{} public static class FundingExpiredException extends RuntimeException{} public static class ConcurrentFundingException extends RuntimeException{} public static class IdempotencyConflictException extends RuntimeException{}
}
