package com.example.campusmarket.integration;

import com.example.campusmarket.legacy.LegacyMarketApplication;
import com.example.campusmarket.warranty.application.SellerObligationService;
import com.example.campusmarket.warranty.application.WarrantyDeadlineScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 MySQL：质保截止领取 fencing，以及截止时刻最后一笔筹资与过期更新串行化。 */
@SpringBootTest(classes = LegacyMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "server.port=18086", "campus.market.payment.provider-url=http://localhost:18086/simulated-provider",
    "campus.market.search.dispatcher.enabled=false", "campus.market.dispute.deadline.enabled=false",
    "campus.market.dispute.return-reconciliation.enabled=false", "campus.market.warranty.deadline.enabled=false",
    "spring.rabbitmq.listener.simple.auto-startup=false", "spring.rabbitmq.listener.direct.auto-startup=false"
})
class WarrantyDeadlineRaceIT extends Task11MySqlContainers {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired SellerObligationService obligations;
    private WarrantyDeadlineScheduler deadlines;

    @BeforeEach
    void createManualDeadlineScheduler() {
        deadlines = new WarrantyDeadlineScheduler(jdbc, transactionManager);
    }

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
            try { funding.get(20, TimeUnit.SECONDS); }
            catch (ExecutionException e) {
                // The deadline winner is a valid serialized business outcome;
                // any other failure must still fail this integration test.
                assertThat(e.getCause()).isInstanceOf(SellerObligationService.FundingExpiredException.class);
            }
            expiry.get(20, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        String state = jdbc.queryForObject("SELECT status FROM seller_obligation WHERE id=?", String.class, obligation.toString());
        assertThat(state).isIn("FUNDED", "CANCELLED");
        long funded = jdbc.queryForObject("SELECT funded_amount_fen FROM seller_obligation WHERE id=?", Long.class, obligation.toString());
        if ("FUNDED".equals(state)) { assertThat(funded).isEqualTo(1000L); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller_account_restriction WHERE source_obligation_id=? AND status='ACTIVE'", Integer.class, obligation.toString())).isZero(); }
        else { assertThat(funded).isLessThan(1000L); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller_account_restriction WHERE source_obligation_id=? AND status='ACTIVE'", Integer.class, obligation.toString())).isEqualTo(2); }
    }

    @Test
    void deadlineCandidateScanDoesNotHoldFundingIndexWhileFundingOwnsPrimaryRow() throws Exception {
        UUID[] f = fixture();
        UUID obligation = UUID.randomUUID();
        jdbc.update("INSERT INTO seller_obligation(id,warranty_case_id,seller_id,obligation_business_key,obligation_amount_fen,funded_amount_fen,funding_deadline,restriction_status,status,version,created_at,updated_at) VALUES (?,?,?, ?,1000,0,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),'RESTRICTED','AWAITING_FUNDING',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", obligation.toString(), f[2].toString(), f[1].toString(), "lock-path-" + obligation);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> expiry = null;
        try (Connection funding = dataSource.getConnection();
             Connection diagnostics = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
            funding.setAutoCommit(false);
            try (PreparedStatement timeout = funding.prepareStatement("SET SESSION innodb_lock_wait_timeout=5")) {
                timeout.execute();
            }
            try (PreparedStatement lock = funding.prepareStatement("SELECT id FROM seller_obligation WHERE id=? FOR UPDATE")) {
                lock.setString(1, obligation.toString());
                lock.executeQuery().close();
            }

            expiry = pool.submit(() -> deadlines.runOnce(100));
            assertThat(awaitSellerObligationLockWait(diagnostics, obligation, expiry, Duration.ofSeconds(10)))
                .as("截止候选事务必须进入 seller_obligation 锁等待")
                .isTrue();
            assertThat(expiry.isDone())
                .as("观察到目标义务锁等待时，截止 Future 必须仍在执行")
                .isFalse();
            boolean fundingIndexLock = hasGrantedFundingIndexLock(diagnostics, obligation);

            SQLException fundingFailure = null;
            try (PreparedStatement update = funding.prepareStatement("UPDATE seller_obligation SET funded_amount_fen=?,status='FUNDED',restriction_status='NONE',version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND seller_id=? AND funded_amount_fen=? AND status IN ('AWAITING_FUNDING','PARTIALLY_FUNDED')")) {
                update.setLong(1, 1000L);
                update.setString(2, obligation.toString());
                update.setString(3, f[1].toString());
                update.setLong(4, 0L);
                int changed = update.executeUpdate();
                assertThat(changed).as("筹资更新必须命中仍处于筹资中的目标义务").isEqualTo(1);
                funding.commit();
            } catch (SQLException e) {
                fundingFailure = e;
                funding.rollback();
            }

            Throwable expiryFailure = null;
            try {
                expiry.get(20, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                expiryFailure = e.getCause();
            }
            String lockEvidence = fundingFailure == null && expiryFailure == null ? "" : innodbLockEvidence(diagnostics);
            assertThat((Object) fundingFailure)
                .as("筹资持有主键时不应因截止候选二级索引锁死锁；candidateFundingIndexLock=%s\n%s", fundingIndexLock, lockEvidence)
                .isNull();
            assertThat((Object) expiryFailure)
                .as("截止候选扫描不应在筹资提交时失败；candidateFundingIndexLock=%s\n%s", fundingIndexLock, lockEvidence)
                .isNull();
            assertThat(fundingIndexLock)
                .as("逐 ID 处理只能持有主键锁，候选扫描不得持有 funding 二级索引锁")
                .isFalse();
        } finally {
            if (expiry != null) expiry.cancel(true);
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private UUID[] fixture() {
        UUID buyer=user(), seller=user(), listing=UUID.randomUUID(), order=UUID.randomUUID(), caseId=UUID.randomUUID();
        jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,'描述','数码',100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",listing.toString(),seller.toString(),"键盘");
        jdbc.update("INSERT INTO trade_order(id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,warranty_days,status,version,t0,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,100,90,'SETTLED',0,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",order.toString(),buyer.toString(),seller.toString(),listing.toString(),"键盘","描述");
        jdbc.update("INSERT INTO warranty_case(id,order_id,idempotency_key,buyer_id,seller_id,warranty_days,warranty_scope_snapshot,disputed_quantity,reason,status,seller_deadline,version,opened_at,created_at,updated_at) VALUES (?,?,?,?,?,90,'scope',1,'FUNCTIONAL_DEFECT','OPEN',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",caseId.toString(),order.toString(),"key-"+caseId,buyer.toString(),seller.toString());
        return new UUID[]{buyer,seller,caseId};
    }
    private UUID user(){return UUID.randomUUID();}
    private static void await(CyclicBarrier barrier) { try { barrier.await(20, TimeUnit.SECONDS); } catch (Exception e) { throw new AssertionError("并发屏障失败", e); } }

    private boolean awaitSellerObligationLockWait(Connection diagnostics, UUID obligation, Future<?> expiry, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (PreparedStatement statement = diagnostics.prepareStatement("SELECT COUNT(*) FROM performance_schema.data_lock_waits waits JOIN performance_schema.data_locks requesting ON requesting.ENGINE_LOCK_ID=waits.REQUESTING_ENGINE_LOCK_ID JOIN performance_schema.data_locks blocking ON blocking.ENGINE_LOCK_ID=waits.BLOCKING_ENGINE_LOCK_ID WHERE requesting.OBJECT_SCHEMA=DATABASE() AND requesting.OBJECT_NAME='seller_obligation' AND blocking.OBJECT_SCHEMA=DATABASE() AND blocking.OBJECT_NAME='seller_obligation' AND COALESCE(requesting.LOCK_DATA,'') LIKE ? AND COALESCE(blocking.LOCK_DATA,'') LIKE ?")) {
                statement.setString(1, "%" + obligation + "%");
                statement.setString(2, "%" + obligation + "%");
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) > 0) return !expiry.isDone();
                }
            }
            Thread.sleep(25L);
        }
        return false;
    }

    private boolean hasGrantedFundingIndexLock(Connection diagnostics, UUID obligation) throws SQLException {
        try (PreparedStatement statement = diagnostics.prepareStatement("SELECT COUNT(*) FROM performance_schema.data_locks WHERE OBJECT_SCHEMA=DATABASE() AND OBJECT_NAME='seller_obligation' AND INDEX_NAME='idx_seller_obligation_funding' AND LOCK_STATUS='GRANTED' AND COALESCE(LOCK_DATA,'') LIKE ?")) {
            statement.setString(1, "%" + obligation + "%");
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1) > 0;
            }
        }
    }

    private String innodbLockEvidence(Connection diagnostics) throws SQLException {
        try (PreparedStatement statement = diagnostics.prepareStatement("SHOW ENGINE INNODB STATUS")) {
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString("Status") : "";
            }
        }
    }
}
