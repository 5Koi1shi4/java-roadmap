package com.example.campusmarket.integration;

import com.example.campusmarket.legacy.LegacyMarketApplication;
import com.example.campusmarket.catalog.application.ListingService;
import com.example.campusmarket.payment.application.SettlementService;
import com.example.campusmarket.shared.Money;
import com.example.campusmarket.warranty.application.SellerObligationService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证结算事实由卖家锁保护，且幂等重放先于后续限制校验。 */
@SpringBootTest(classes = LegacyMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestPropertySource(properties = {
    "campus.market.search.dispatcher.enabled=false", "campus.market.dispute.deadline.enabled=false",
    "campus.market.dispute.return-reconciliation.enabled=false", "campus.market.warranty.deadline.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SellerWithdrawalConcurrencyIT extends Task11MySqlContainers {
    @Autowired JdbcTemplate jdbc;
    @Autowired ListingService listings;
    @Autowired SettlementService settlements;
    @Autowired SellerObligationService obligations;
    private final JdbcTemplate lockObserver = new JdbcTemplate(
        new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));

    @BeforeAll
    static void migrate() { Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate(); }

    @Test
    void concurrentRequestsCannotSpendTheSameSettlementAndReplaySurvivesRestriction() throws Exception {
        Fixture fixture = fixture();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> first = pool.submit(() -> listings.withdraw(fixture.seller(), 700, "w-1"));
            Future<UUID> second = pool.submit(() -> listings.withdraw(fixture.seller(), 700, "w-2"));
            int successes = 0;
            String winnerKey = null; UUID existing = null;
            Future<?>[] attempts = new Future<?>[]{first, second};
            String[] keys = {"w-1", "w-2"};
            for (int i = 0; i < attempts.length; i++) {
                Future<?> f = attempts[i];
                try { existing = (UUID) f.get(20, TimeUnit.SECONDS); winnerKey = keys[i]; successes++; }
                catch (ExecutionException e) { assertThat(e.getCause()).isInstanceOf(ListingService.InsufficientBalanceException.class); }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(amount_fen),0) FROM seller_withdrawal WHERE seller_id=? AND status='REQUESTED'", Long.class, fixture.seller().toString())).isEqualTo(700L);

            jdbc.update("INSERT INTO seller_account_restriction(seller_id,restriction_type,source_obligation_id,status,created_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6))", fixture.seller().toString(), "WITHDRAW", fixture.obligation().toString());
            assertThat(winnerKey).isNotNull();
            assertThat(listings.withdraw(fixture.seller(), 700, winnerKey)).isEqualTo(existing);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void settlementAndWithdrawalWaitForTheSameSellerLockWhenNoSettlementExists() throws Exception {
        Fixture fixture = settleableFixture();
        ensureSellerLockRow(fixture.seller());
        try (Connection heldSellerLock = holdSellerLock(fixture.seller())) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<UUID> withdrawal = pool.submit(
                    () -> listings.withdraw(fixture.seller(), 700, "interleave-withdraw"));
                Future<SettlementService.SettlementResult> settlement = pool.submit(
                    () -> settlements.settle(fixture.order()));

                assertThat(completedWithin(withdrawal, 500, TimeUnit.MILLISECONDS))
                    .as("withdrawal must wait on the durable seller lock")
                    .isFalse();
                assertThat(completedWithin(settlement, 500, TimeUnit.MILLISECONDS))
                    .as("settlement must wait on the same durable seller lock")
                    .isFalse();

                heldSellerLock.commit();
                SettlementService.SettlementResult settled = settlement.get(15, TimeUnit.SECONDS);
                assertThat(settled.status()).isEqualTo("SETTLED");
                try {
                    withdrawal.get(15, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ListingService.InsufficientBalanceException.class);
                }
                assertThat(jdbc.queryForObject(
                    "SELECT COALESCE(SUM(amount_fen),0) FROM seller_withdrawal WHERE seller_id=? AND status IN ('REQUESTED','COMPLETED')",
                    Long.class, fixture.seller().toString())).isLessThanOrEqualTo(1000L);
            } finally {
                pool.shutdownNow();
                assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void settlementReadsEligibilityAfterWaitingForSellerLock() throws Exception {
        Fixture fixture = settleableFixture();
        ensureSellerLockRow(fixture.seller());
        try (Connection heldSellerLock = holdSellerLock(fixture.seller())) {
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<SettlementService.SettlementResult> settlement = pool.submit(
                    () -> settlements.settle(fixture.order()));
                assertThat(awaitSellerLockWait(5, TimeUnit.SECONDS))
                    .as("结算必须已在等待卖家锁后才能提交新的资格事实")
                    .isTrue();
                UUID dispute = UUID.randomUUID();
                jdbc.update("INSERT INTO dispute_case(id,order_id,initiator_id,disputed_quantity,reason,status,seller_deadline,version,opened_at,created_at,updated_at) "
                        + "VALUES (?,?,?,1,'QUANTITY','OPEN',DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                    dispute.toString(), fixture.order().toString(), UUID.randomUUID().toString());
                heldSellerLock.commit();
                SettlementService.SettlementResult result = settlement.get(15, TimeUnit.SECONDS);
                assertThat(result.status()).isEqualTo("BLOCKED");
                assertThat(result.blockedReason()).isEqualTo("ACTIVE_DISPUTE");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement WHERE order_id=?", Integer.class,
                    fixture.order().toString())).isZero();
            } finally {
                pool.shutdownNow();
                assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void multipleSettlementsAreSerializedByOneSellerLock() throws Exception {
        Fixture fixture = fixture();
        insertAdditionalSettledOrder(fixture.seller(), 400);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> first = pool.submit(() -> listings.withdraw(fixture.seller(), 900, "multi-w-1"));
            Future<UUID> second = pool.submit(() -> listings.withdraw(fixture.seller(), 900, "multi-w-2"));
            int successes = 0;
            for (Future<?> attempt : new Future<?>[]{first, second}) {
                try {
                    attempt.get(15, TimeUnit.SECONDS);
                    successes++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ListingService.InsufficientBalanceException.class);
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_fen),0) FROM seller_withdrawal WHERE seller_id=? AND status IN ('REQUESTED','COMPLETED')",
                Long.class, fixture.seller().toString())).isEqualTo(900L);
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM seller_balance_lock WHERE seller_id=?", Integer.class, fixture.seller().toString()))
                .isEqualTo(1);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void deductionWaitsForTheSameSellerLockAsWithdrawal() throws Exception {
        Fixture fixture = fixture();
        UUID settlement = UUID.fromString(jdbc.queryForObject(
            "SELECT id FROM settlement WHERE order_id=?", String.class, fixture.order().toString()));
        ensureSellerLockRow(fixture.seller());
        try (Connection heldSellerLock = holdSellerLock(fixture.seller())) {
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<Long> deduction = pool.submit(
                    () -> obligations.deductFutureSettlement(settlement, fixture.obligation(), Money.ofFen(50)));
                assertThat(completedWithin(deduction, 500, TimeUnit.MILLISECONDS))
                    .as("future-settlement deduction must wait on the durable seller lock")
                    .isFalse();
                heldSellerLock.commit();
                assertThat(deduction.get(15, TimeUnit.SECONDS)).isEqualTo(50L);
            } finally {
                pool.shutdownNow();
                assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void deductionReadsSettlementCreatedAfterWaitingForSellerLock() throws Exception {
        Fixture fixture = fixture(false);
        UUID settlement = UUID.randomUUID();
        ensureSellerLockRow(fixture.seller());
        try (Connection heldSellerLock = holdSellerLock(fixture.seller())) {
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<Long> deduction = pool.submit(
                    () -> obligations.deductFutureSettlement(settlement, fixture.obligation(), Money.ofFen(50)));
                assertThat(awaitSellerLockWait(5, TimeUnit.SECONDS))
                    .as("抵扣必须已在等待卖家锁后才能读取新的结算事实")
                    .isTrue();
                jdbc.update("INSERT INTO settlement(id,order_id,paid_amount_fen,successful_refund_fen,net_settlement_fen,status,created_at) "
                        + "VALUES (?,?,1000,0,1000,'SETTLED',CURRENT_TIMESTAMP(6))",
                    settlement.toString(), fixture.order().toString());
                heldSellerLock.commit();
                assertThat(deduction.get(15, TimeUnit.SECONDS)).isEqualTo(50L);
                assertThat(jdbc.queryForObject("SELECT net_settlement_fen FROM settlement WHERE id=?", Long.class,
                    settlement.toString())).isEqualTo(950L);
            } finally {
                pool.shutdownNow();
                assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private Fixture fixture() {
        return fixture(true);
    }

    private Fixture fixture(boolean includeSettlement) {
        UUID seller = user(), buyer = user(), listing = UUID.randomUUID(), order = UUID.randomUUID(), settlement = UUID.randomUUID(), caseId = UUID.randomUUID(), obligation = UUID.randomUUID();
        jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,'desc','digital',100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString(), "keyboard");
        jdbc.update("INSERT INTO trade_order(id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,warranty_days,status,version,t0,created_at,updated_at) VALUES (?,?,?,?,?,?,1000,1,1000,1000,90,'SETTLED',0,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 8 DAY),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", order.toString(), buyer.toString(), seller.toString(), listing.toString(), "keyboard", "desc");
        if (includeSettlement) {
            jdbc.update("INSERT INTO settlement(id,order_id,paid_amount_fen,successful_refund_fen,net_settlement_fen,status,created_at) VALUES (?,?,1000,0,1000,'SETTLED',CURRENT_TIMESTAMP(6))", settlement.toString(), order.toString());
        }
        jdbc.update("INSERT INTO warranty_case(id,order_id,idempotency_key,buyer_id,seller_id,warranty_days,warranty_scope_snapshot,disputed_quantity,reason,status,seller_deadline,version,opened_at,created_at,updated_at) VALUES (?,?,?,?,?,90,'scope',1,'FUNCTIONAL_DEFECT','OPEN',DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 3 DAY),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", caseId.toString(), order.toString(), "withdraw-" + caseId, buyer.toString(), seller.toString());
        jdbc.update("INSERT INTO seller_obligation(id,warranty_case_id,seller_id,obligation_business_key,obligation_amount_fen,funding_deadline,restriction_status,status,version,created_at,updated_at) VALUES (?,?,?, ?,100,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 3 DAY),'RESTRICTED','AWAITING_FUNDING',1,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", obligation.toString(), caseId.toString(), seller.toString(), "withdraw-" + obligation);
        return new Fixture(seller, obligation, order);
    }

    private Fixture settleableFixture() {
        UUID seller = user(), buyer = user(), listing = UUID.randomUUID(), order = UUID.randomUUID(), payment = UUID.randomUUID();
        jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,'desc','digital',100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString(), "keyboard");
        jdbc.update("INSERT INTO trade_order(id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,warranty_days,status,version,t0,created_at,updated_at) VALUES (?,?,?,?,?,?,1000,1,1000,1000,90,'AFTERSALE_WINDOW',0,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 8 DAY),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", order.toString(), buyer.toString(), seller.toString(), listing.toString(), "keyboard", "desc");
        jdbc.update("INSERT INTO payment_order(id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status,created_at,updated_at) VALUES (?,?,?,?,1000,1000,?,'SUCCEEDED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", payment.toString(), order.toString(), "simulated", "pay-" + payment, "sim-pay-" + payment);
        return new Fixture(seller, null, order);
    }

    private void insertAdditionalSettledOrder(UUID seller, long netSettlement) {
        UUID buyer = user(), listing = UUID.randomUUID(), order = UUID.randomUUID(), settlement = UUID.randomUUID();
        jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,'desc','digital',100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString(), "second-keyboard");
        jdbc.update("INSERT INTO trade_order(id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,warranty_days,status,version,t0,created_at,updated_at) VALUES (?,?,?,?,?,?,?,1,?,?,90,'SETTLED',0,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 8 DAY),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", order.toString(), buyer.toString(), seller.toString(), listing.toString(), "second-keyboard", "desc", netSettlement, netSettlement, netSettlement);
        jdbc.update("INSERT INTO settlement(id,order_id,paid_amount_fen,successful_refund_fen,net_settlement_fen,status,created_at) VALUES (?,?,?,0,?,'SETTLED',CURRENT_TIMESTAMP(6))", settlement.toString(), order.toString(), netSettlement, netSettlement);
    }

    private void ensureSellerLockRow(UUID seller) {
        jdbc.update("INSERT INTO seller_balance_lock(seller_id) VALUES (?) ON DUPLICATE KEY UPDATE seller_id=VALUES(seller_id)", seller.toString());
    }

    private Connection holdSellerLock(UUID seller) throws Exception {
        Connection connection = MYSQL.createConnection("");
        connection.setAutoCommit(false);
        try (PreparedStatement lock = connection.prepareStatement(
            "SELECT seller_id FROM seller_balance_lock WHERE seller_id=? FOR UPDATE")) {
            lock.setString(1, seller.toString());
            try (var rows = lock.executeQuery()) {
                assertThat(rows.next()).isTrue();
            }
        }
        return connection;
    }

    private boolean completedWithin(Future<?> future, long timeout, TimeUnit unit) {
        try {
            future.get(timeout, unit);
            return true;
        } catch (TimeoutException expected) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return true;
        } catch (ExecutionException completedWithFailure) {
            return true;
        }
    }

    private boolean awaitSellerLockWait(long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            Long waits = lockObserver.queryForObject(
                "SELECT COUNT(*) FROM performance_schema.data_lock_waits", Long.class);
            if (waits != null && waits > 0) return true;
            Thread.sleep(25);
        }
        return false;
    }
    private UUID user() { return UUID.randomUUID(); }
    private record Fixture(UUID seller, UUID obligation, UUID order) {}
}
