package com.example.campusmarket.warranty.application;

import com.example.campusmarket.payment.application.RefundService;
import com.example.campusmarket.shared.Money;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 义务筹资、未来结算抵扣与限制解除；所有累计更新均由 MySQL 条件更新保证幂等。 */
@Service
@Profile("!test")
public final class SellerObligationService {
    private final JdbcTemplate jdbc; private final TransactionTemplate transactions; private final RefundService refunds;
    public SellerObligationService(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager tx, RefundService refunds) { this.jdbc=Objects.requireNonNull(jdbc); this.transactions=new TransactionTemplate(Objects.requireNonNull(tx)); this.refunds=Objects.requireNonNull(refunds); }

    public FundingResult fundObligation(UUID obligationId, UUID sellerId, Money amount) {
        Objects.requireNonNull(amount,"筹资金额不能为空");
        FundingResult result=transactions.execute(s -> {
            Obligation row=jdbc.query("SELECT id,warranty_case_id,seller_id,obligation_amount_fen,funded_amount_fen,status,funding_deadline FROM seller_obligation WHERE id=? FOR UPDATE",rs->rs.next()?new Obligation(UUID.fromString(rs.getString(1)),UUID.fromString(rs.getString(2)),UUID.fromString(rs.getString(3)),rs.getLong(4),rs.getLong(5),rs.getString(6),rs.getTimestamp(7).toInstant()):null,obligationId.toString());
            if(row==null||!row.sellerId().equals(sellerId)) throw new NotFoundException();
            Instant now=dbNow(); if(!now.isBefore(row.deadline())&&row.funded()<row.amount()) { jdbc.update("UPDATE seller_obligation SET status='CANCELLED',restriction_status='RESTRICTED',updated_at=? WHERE id=? AND status<>'FUNDED'",Timestamp.from(now),obligationId.toString()); throw new FundingExpiredException(); }
            if(row.funded()==row.amount()) return new FundingResult(obligationId,row.caseId(),row.amount(),row.funded(),"FUNDED");
            long next; try{next=Math.addExact(row.funded(),amount.fen());}catch(ArithmeticException e){throw new IllegalArgumentException("筹资金额溢出",e);}
            if(next>row.amount()) throw new IllegalArgumentException("筹资金额超出义务");
            String state=next==row.amount()?"FUNDED":"PARTIALLY_FUNDED";
            if(jdbc.update("UPDATE seller_obligation SET funded_amount_fen=?,status=?,restriction_status=?,version=version+1,updated_at=? WHERE id=? AND seller_id=? AND funded_amount_fen=? AND status IN ('AWAITING_FUNDING','PARTIALLY_FUNDED')",next,state,next==row.amount()?"NONE":"RESTRICTED",Timestamp.from(now),obligationId.toString(),sellerId.toString(),row.funded())!=1) throw new ConcurrentFundingException();
            if(next==row.amount()) clearRestrictions(sellerId,obligationId,now);
            return new FundingResult(obligationId,row.caseId(),row.amount(),next,state);
        });
        if(result!=null&&"FUNDED".equals(result.status())) {
            UUID orderId=jdbc.queryForObject("SELECT order_id FROM warranty_case WHERE id=?",(rs,n)->UUID.fromString(rs.getString(1)),result.warrantyCaseId().toString());
            refunds.requestRefund(orderId,"warranty-refund-"+obligationId,Money.ofFen(result.obligationAmountFen()),"WARRANTY",result.warrantyCaseId());
        }
        return result;
    }

    /** 按最早到期义务抵扣一笔未来结算；(settlementId, obligationId) 唯一键提供幂等。 */
    public long deductFutureSettlement(UUID settlementId, UUID obligationId, Money amount) {
        Objects.requireNonNull(settlementId); Objects.requireNonNull(obligationId); Objects.requireNonNull(amount);
        return transactions.execute(s->{
            Obligation row=jdbc.query("SELECT id,seller_id,obligation_amount_fen,funded_amount_fen,status FROM seller_obligation WHERE id=? FOR UPDATE",rs->rs.next()?new Obligation(UUID.fromString(rs.getString(1)),null,UUID.fromString(rs.getString(2)),rs.getLong(3),rs.getLong(4),rs.getString(5),null):null,obligationId.toString());
            if(row==null) throw new NotFoundException();
            long remaining=row.amount()-row.funded(); if(remaining<=0) return 0L;
            long deduction=Math.min(amount.fen(),remaining);
            int inserted=jdbc.update("INSERT INTO settlement_obligation_deduction(id,settlement_id,obligation_id,amount_fen,created_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id",UUID.randomUUID().toString(),settlementId.toString(),obligationId.toString(),deduction);
            if(inserted==1){ long next=row.funded()+deduction; jdbc.update("UPDATE seller_obligation SET funded_amount_fen=?,status=?,restriction_status=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND funded_amount_fen=?",next,next==row.amount()?"FUNDED":"PARTIALLY_FUNDED",next==row.amount()?"NONE":"RESTRICTED",obligationId.toString(),row.funded()); if(next==row.amount()) clearRestrictions(row.sellerId(),obligationId,dbNow()); }
            return deduction;
        });
    }
    public boolean isRestricted(UUID sellerId,String type){ Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM seller_account_restriction WHERE seller_id=? AND restriction_type=? AND status='ACTIVE'",Integer.class,sellerId.toString(),type); return n!=null&&n>0; }
    private void clearRestrictions(UUID seller,UUID obligation,Instant now){jdbc.update("UPDATE seller_account_restriction SET status='CLEARED',cleared_at=? WHERE seller_id=? AND source_obligation_id=? AND status='ACTIVE'",Timestamp.from(now),seller.toString(),obligation.toString());}
    private Instant dbNow(){return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class).toInstant();}
    private record Obligation(UUID id,UUID caseId,UUID sellerId,long amount,long funded,String status,Instant deadline){}
    public record FundingResult(UUID obligationId,UUID warrantyCaseId,long obligationAmountFen,long fundedAmountFen,String status){}
    public static class NotFoundException extends RuntimeException{} public static class FundingExpiredException extends RuntimeException{} public static class ConcurrentFundingException extends RuntimeException{}
}
