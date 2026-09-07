package com.example.campusmarket.warranty.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** 质保卖家 72 小时、管理员 7/14 天截止调度；领取使用数据库时间和 token fencing。 */
@Component @Profile("!test") @EnableScheduling
@ConditionalOnProperty(prefix="campus.market.warranty.deadline",name="enabled",havingValue="true",matchIfMissing=true)
public final class WarrantyDeadlineScheduler {
    private final JdbcTemplate jdbc; private final TransactionTemplate tx; private final String owner="warranty-deadline-"+UUID.randomUUID();
    public WarrantyDeadlineScheduler(JdbcTemplate jdbc,org.springframework.transaction.PlatformTransactionManager manager){this.jdbc=Objects.requireNonNull(jdbc);this.tx=new TransactionTemplate(Objects.requireNonNull(manager));}
    @Scheduled(initialDelayString="${campus.market.warranty.deadline.initial-delay-ms:0}",fixedDelayString="${campus.market.warranty.deadline.fixed-delay-ms:1000}") public void dispatch(){runOnce(50);}
    public int runOnce(int limit){if(limit<=0||limit>1000)throw new IllegalArgumentException("批量大小无效");List<UUID> ids=jdbc.query("SELECT id FROM warranty_deadline_claim WHERE due_at<=CURRENT_TIMESTAMP(6) AND (status='NEW' OR (status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP(6))) ORDER BY due_at,id LIMIT ?",(rs,n)->UUID.fromString(rs.getString(1)),limit);int done=0;for(UUID id:ids){String token=UUID.randomUUID().toString();if(jdbc.update("UPDATE warranty_deadline_claim SET status='PROCESSING',owner_id=?,claim_token=?,lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 30 SECOND),attempt_count=attempt_count+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND due_at<=CURRENT_TIMESTAMP(6) AND (status='NEW' OR (status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP(6)))",owner,token,id.toString())==1&&process(id,token))done++;} expireObligations(); return done;}
    private void expireObligations(){
        List<UUID> ids=jdbc.query("SELECT id FROM seller_obligation WHERE status IN ('AWAITING_FUNDING','PARTIALLY_FUNDED') AND funded_amount_fen<obligation_amount_fen AND funding_deadline<=CURRENT_TIMESTAMP(6) FOR UPDATE",(rs,n)->UUID.fromString(rs.getString(1)));
        for(UUID id:ids) tx.executeWithoutResult(s -> {
            Obligation row=jdbc.query("SELECT seller_id,obligation_amount_fen,funded_amount_fen,version FROM seller_obligation WHERE id=? FOR UPDATE",rs->rs.next()?new Obligation(UUID.fromString(rs.getString(1)),rs.getLong(2),rs.getLong(3),rs.getLong(4)):null,id.toString());
            if(row==null||row.funded()>=row.amount()) return;
            if(jdbc.update("UPDATE seller_obligation SET status='CANCELLED',restriction_status='RESTRICTED',version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('AWAITING_FUNDING','PARTIALLY_FUNDED') AND funded_amount_fen<obligation_amount_fen AND funding_deadline<=CURRENT_TIMESTAMP(6)",id.toString())==1){
                recordTransition(row.seller(),"SELLER_OBLIGATION_EXPIRED",id,row.version()+1,"{\"fundedAmountFen\":"+row.funded()+",\"obligationAmountFen\":"+row.amount()+"}");
            }
        });
    }
    public int runOne(UUID caseId){List<UUID> ids=jdbc.query("SELECT id FROM warranty_deadline_claim WHERE warranty_case_id=? AND due_at<=CURRENT_TIMESTAMP(6) AND (status='NEW' OR (status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP(6))) ORDER BY due_at,id",(rs,n)->UUID.fromString(rs.getString(1)),caseId.toString());int done=0;for(UUID id:ids){String token=UUID.randomUUID().toString();if(jdbc.update("UPDATE warranty_deadline_claim SET status='PROCESSING',owner_id=?,claim_token=?,lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 30 SECOND),attempt_count=attempt_count+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND due_at<=CURRENT_TIMESTAMP(6) AND (status='NEW' OR (status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP(6)))",owner,token,id.toString())==1&&process(id,token))done++;}expireObligations();return done;}
    private boolean process(UUID id,String token){try{return Boolean.TRUE.equals(tx.execute(s->{
        // Every path locks the case before the claim. This matches assignment and
        // prevents the scheduler/assignment deadlock while retaining fencing.
        Claim candidate=jdbc.query("SELECT warranty_case_id,deadline_type,owner_id,claim_token,lease_until FROM warranty_deadline_claim WHERE id=?",rs->rs.next()?new Claim(UUID.fromString(rs.getString(1)),rs.getString(2),rs.getString(3),rs.getString(4),rs.getTimestamp(5).toInstant()):null,id.toString());
        if(candidate==null)return false; Instant now=dbNow();
        CaseRow row=jdbc.query("SELECT status,seller_deadline,admin_deadline,hard_deadline FROM warranty_case WHERE id=? FOR UPDATE",rs->rs.next()?new CaseRow(rs.getString(1),ts(rs.getTimestamp(2)),ts(rs.getTimestamp(3)),ts(rs.getTimestamp(4))):null,candidate.caseId().toString());
        Claim c=jdbc.query("SELECT warranty_case_id,deadline_type,owner_id,claim_token,lease_until FROM warranty_deadline_claim WHERE id=? FOR UPDATE",rs->rs.next()?new Claim(UUID.fromString(rs.getString(1)),rs.getString(2),rs.getString(3),rs.getString(4),rs.getTimestamp(5).toInstant()):null,id.toString());
        if(c==null||!owner.equals(c.owner())||!token.equals(c.token())||!c.lease().isAfter(now))return false;
        if(row==null)return complete(id,token);if("SELLER_RESPONSE".equals(c.type())){if(now.isBefore(row.seller()))return defer(id,token,row.seller());if("OPEN".equals(row.status())){Instant admin=now.plus(java.time.Duration.ofDays(7)),hard=now.plus(java.time.Duration.ofDays(14));jdbc.update("UPDATE warranty_case SET status='UNDER_REVIEW',admin_deadline=?,hard_deadline=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='OPEN'",Timestamp.from(admin),Timestamp.from(hard),c.caseId().toString());arm(c.caseId(),admin,hard);}}else if("ADMIN_SLA".equals(c.type())){if(row.admin()==null||now.isBefore(row.admin()))return defer(id,token,row.admin()==null?now.plusSeconds(1):row.admin());jdbc.update("UPDATE warranty_case SET status=CASE WHEN status IN ('OPEN','SELLER_RESPONDED','UNDER_REVIEW') THEN 'ESCALATED' ELSE status END,admin_sla_alerted_at=COALESCE(admin_sla_alerted_at,CURRENT_TIMESTAMP(6)),updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",c.caseId().toString());}else if("HARD_DEADLINE".equals(c.type())){if(row.hard()==null||now.isBefore(row.hard()))return defer(id,token,row.hard()==null?now.plusSeconds(1):row.hard());jdbc.update("UPDATE warranty_case SET status=CASE WHEN status IN ('OPEN','SELLER_RESPONDED','UNDER_REVIEW') THEN 'ESCALATED' ELSE status END,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",c.caseId().toString());}return complete(id,token);}));}catch(RuntimeException e){jdbc.update("UPDATE warranty_deadline_claim SET status='NEW',owner_id=NULL,claim_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND owner_id=? AND claim_token=? AND lease_until>CURRENT_TIMESTAMP(6)",id.toString(),owner,token);throw e;}}
    private void arm(UUID caseId,Instant admin,Instant hard){
        String adminId=UUID.randomUUID().toString(),hardId=UUID.randomUUID().toString();
        // Arming is idempotent. In particular, never reset a claim already
        // PROCESSING/COMPLETED: doing so would steal its owner/token and move
        // the fixed admin/hard deadline after another worker has claimed it.
        jdbc.update("INSERT INTO warranty_deadline_claim(id,warranty_case_id,deadline_type,due_at,status,created_at,updated_at) VALUES (?,?, 'ADMIN_SLA',?,'NEW',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),(?,?, 'HARD_DEADLINE',?,'NEW',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE due_at=CASE WHEN status='NEW' THEN LEAST(due_at,VALUES(due_at)) ELSE due_at END,updated_at=CURRENT_TIMESTAMP(6)",adminId,caseId.toString(),Timestamp.from(admin),hardId,caseId.toString(),Timestamp.from(hard));
    }
    private boolean defer(UUID id,String token,Instant due){return jdbc.update("UPDATE warranty_deadline_claim SET status='NEW',due_at=?,owner_id=NULL,claim_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='PROCESSING' AND owner_id=? AND claim_token=? AND lease_until>CURRENT_TIMESTAMP(6)",Timestamp.from(due),id.toString(),owner,token)==1;}
    private boolean complete(UUID id,String token){return jdbc.update("UPDATE warranty_deadline_claim SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP(6),owner_id=NULL,claim_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='PROCESSING' AND owner_id=? AND claim_token=? AND lease_until>CURRENT_TIMESTAMP(6)",id.toString(),owner,token)==1;}
    private Instant dbNow(){return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class).toInstant();}private static Instant ts(Timestamp t){return t==null?null:t.toInstant();}
    private void recordTransition(UUID seller,String action,UUID obligation,long version,String payload){
        UUID event=UUID.nameUUIDFromBytes((action+":"+obligation+":"+version).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO audit_event(id,actor_id,action,resource_type,resource_id,result,details,occurred_at) VALUES (?,?,?,'SELLER_OBLIGATION',?,'SUCCESS',CAST(? AS JSON),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id",event.toString(),seller.toString(),action,obligation.toString(),payload);
        jdbc.update("INSERT INTO integration_outbox(id,event_id,event_type,aggregate_id,aggregate_version,schema_version,occurred_at,payload,status,attempt_count,available_at,created_at) VALUES (?,?,?, ?,?,1,CURRENT_TIMESTAMP(6),CAST(? AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id",event.toString(),event.toString(),action,obligation.toString(),version,payload);
    }
    private record Claim(UUID caseId,String type,String owner,String token,Instant lease){} private record CaseRow(String status,Instant seller,Instant admin,Instant hard){}
    private record Obligation(UUID seller,long amount,long funded,long version){}
}
