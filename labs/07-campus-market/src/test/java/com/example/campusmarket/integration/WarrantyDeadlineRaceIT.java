package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.warranty.application.SellerObligationService;
import com.example.campusmarket.warranty.application.WarrantyDeadlineScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 MySQL：质保截止领取 fencing，以及截止时刻最后一笔筹资与过期更新串行化。 */
@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "server.port=18086", "campus.market.payment.provider-url=http://localhost:18086/simulated-provider",
    "campus.market.search.dispatcher.enabled=false", "campus.market.dispute.deadline.enabled=false",
    "campus.market.dispute.return-reconciliation.enabled=false", "campus.market.warranty.deadline.enabled=true",
    "spring.rabbitmq.listener.simple.auto-startup=false", "spring.rabbitmq.listener.direct.auto-startup=false"
})
class WarrantyDeadlineRaceIT extends Task11MySqlContainers {
    @Autowired JdbcTemplate jdbc;
    @Autowired WarrantyDeadlineScheduler deadlines;
    @Autowired SellerObligationService obligations;

    @Test
    void expiredSellerClaimIsTakenOverWithFreshTokenAndAdminDeadlinesUseDatabaseTime() {
        UUID[] f = fixture();
        UUID claim = UUID.randomUUID();
        jdbc.update("INSERT INTO warranty_deadline_claim(id,warranty_case_id,deadline_type,due_at,status,owner_id,claim_token,lease_until,created_at,updated_at) VALUES (?,?, 'SELLER_RESPONSE',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),'PROCESSING','old-owner','old-token',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", claim.toString(), f[2].toString());
        assertThat(deadlines.runOne(f[2])).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM warranty_case WHERE id=?", String.class, f[2].toString())).isEqualTo("UNDER_REVIEW");
        assertThat(jdbc.queryForObject("SELECT status FROM warranty_deadline_claim WHERE id=?", String.class, claim.toString())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT owner_id FROM warranty_deadline_claim WHERE id=?", String.class, claim.toString())).isNull();
        assertThat(jdbc.update("UPDATE warranty_deadline_claim SET status='COMPLETED' WHERE id=? AND owner_id='old-owner' AND claim_token='old-token'", claim.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT due_at > CURRENT_TIMESTAMP(6) FROM warranty_deadline_claim WHERE warranty_case_id=? AND deadline_type='ADMIN_SLA'", Boolean.class, f[2].toString())).isTrue();
    }

    @Test
    void fundingAndExpiredObligationAtSameInstantHaveOneSerializedOutcome() throws Exception {
        UUID[] f = fixture();
        UUID obligation = UUID.randomUUID();
        jdbc.update("INSERT INTO seller_obligation(id,warranty_case_id,seller_id,obligation_business_key,obligation_amount_fen,funded_amount_fen,funding_deadline,restriction_status,status,version,created_at,updated_at) VALUES (?,?,?, ?,1000,0,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),'RESTRICTED','AWAITING_FUNDING',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", obligation.toString(), f[2].toString(), f[1].toString(), "race-" + obligation);
        jdbc.update("INSERT INTO seller_account_restriction(seller_id,restriction_type,source_obligation_id,status,created_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6)),(?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6))", f[1].toString(), "PUBLISH", obligation.toString(), f[1].toString(), "WITHDRAW", obligation.toString());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<?> funding = pool.submit(() -> { await(barrier); obligations.fundObligation(obligation, f[1], com.example.campusmarket.shared.Money.ofFen(1000)); });
            Future<?> expiry = pool.submit(() -> { await(barrier); deadlines.runOnce(100); });
            funding.get(20, TimeUnit.SECONDS); expiry.get(20, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        String state = jdbc.queryForObject("SELECT status FROM seller_obligation WHERE id=?", String.class, obligation.toString());
        assertThat(state).isIn("FUNDED", "CANCELLED");
        long funded = jdbc.queryForObject("SELECT funded_amount_fen FROM seller_obligation WHERE id=?", Long.class, obligation.toString());
        if ("FUNDED".equals(state)) { assertThat(funded).isEqualTo(1000L); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller_account_restriction WHERE source_obligation_id=? AND status='ACTIVE'", Integer.class, obligation.toString())).isZero(); }
        else { assertThat(funded).isLessThan(1000L); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller_account_restriction WHERE source_obligation_id=? AND status='ACTIVE'", Integer.class, obligation.toString())).isEqualTo(2); }
    }

    private UUID[] fixture() {
        UUID buyer=user(), seller=user(), listing=UUID.randomUUID(), order=UUID.randomUUID(), caseId=UUID.randomUUID();
        jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,'描述','数码',100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",listing.toString(),seller.toString(),"键盘");
        jdbc.update("INSERT INTO trade_order(id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,warranty_days,status,version,t0,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,100,90,'SETTLED',0,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",order.toString(),buyer.toString(),seller.toString(),listing.toString(),"键盘","描述");
        jdbc.update("INSERT INTO warranty_case(id,order_id,idempotency_key,buyer_id,seller_id,warranty_days,warranty_scope_snapshot,disputed_quantity,reason,status,seller_deadline,version,opened_at,created_at,updated_at) VALUES (?,?,?,?,?,90,'scope',1,'FUNCTIONAL_DEFECT','OPEN',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",caseId.toString(),order.toString(),"key-"+caseId,buyer.toString(),seller.toString());
        return new UUID[]{buyer,seller,caseId};
    }
    private UUID user(){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO campus_user(id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",id.toString(),id+"@stu.example.edu.cn","hash");return id;}
    private static void await(CyclicBarrier barrier) { try { barrier.await(20, TimeUnit.SECONDS); } catch (Exception e) { throw new AssertionError("并发屏障失败", e); } }
}
