package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.dispute.application.DisputeDeadlineScheduler;
import com.example.campusmarket.dispute.application.DisputeService;
import com.example.campusmarket.dispute.application.ProofAuthority;
import com.example.campusmarket.dispute.application.ReturnResolutionService;
import com.example.campusmarket.payment.application.RefundService;
import com.example.campusmarket.payment.infrastructure.SimulatedPaymentProviderController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 MySQL：卖家期限、管理员 SLA 与硬期限按数据库时间串行处理。 */
@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {"server.port=18083", "campus.market.payment.provider-url=http://localhost:18083/simulated-provider",
    "campus.market.search.dispatcher.enabled=false", "campus.market.dispute.deadline.enabled=true",
    "campus.market.dispute.deadline.initial-delay-ms=86400000", "campus.market.dispute.deadline.fixed-delay-ms=86400000",
    "campus.market.dispute.return-reconciliation.enabled=false"})
class DisputeDeadlineIT extends Task11MySqlContainers {
    @Autowired JdbcTemplate jdbc;
    /** root 连接只读取 Performance Schema，观察截止事务实际等待的业务行锁。 */
    private final JdbcTemplate lockObserver = new JdbcTemplate(
        new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
    @Autowired DisputeDeadlineScheduler deadlines;
    @Autowired RefundService refunds;
    @Autowired ReturnResolutionService returns;
    @Autowired SimulatedPaymentProviderController provider;
    @Autowired DisputeService disputes;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void ordinaryRefundWinsAgainstStaleHardDeadlineAndKeepsRefundOnlyResolution() throws Exception {
        UUID buyer = user(), seller = user(), admin = user(), listing = UUID.randomUUID(), order = UUID.randomUUID(), payment = UUID.randomUUID(), dispute = UUID.randomUUID();
        insertOrder(buyer, seller, listing, order, "DISPUTED");
        jdbc.update("INSERT INTO payment_order (id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status,created_at,updated_at) VALUES (?,?,?,?,100,100,?,'SUCCEEDED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", payment.toString(), order.toString(), "simulated", "pay-" + payment, "sim-pay-" + payment);
        insertHardCase(dispute, order, buyer, admin);
        claim(dispute, "HARD_DEADLINE");
        CountDownLatch orderLocked = new CountDownLatch(1);
        CountDownLatch allowOrdinary = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var ordinary = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                lockOrder(order, orderLocked);
                awaitBarrier(allowOrdinary);
                return returns.resolve(dispute, com.example.campusmarket.dispute.domain.DisputeDecision.REFUND_ONLY, 1,
                    com.example.campusmarket.dispute.domain.ReturnProofType.SELLER_CONFIRMED, seller.toString(), ProofAuthority.seller(seller));
            }));
            assertThat(orderLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var hard = pool.submit(() -> deadlines.runOne(dispute));
            awaitOrderLockWait(order);
            allowOrdinary.countDown();
            assertThat(ordinary.get(20, TimeUnit.SECONDS).refundStatus()).isEqualTo("PROCESSING");
            assertThat(hard.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            allowOrdinary.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM dispute_case WHERE id=?", String.class, dispute.toString())).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_case WHERE dispute_case_id=?", Integer.class, dispute.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT resolution_type FROM return_case WHERE dispute_case_id=?", String.class, dispute.toString())).isEqualTo("REFUND_ONLY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_order WHERE order_id=?", Integer.class, order.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT quarantined_quantity FROM listing WHERE id=?", Integer.class, listing.toString())).isZero();
    }

    @Test
    void ordinaryRejectWinsAgainstStaleHardDeadlineWithoutCreatingRefundIntent() throws Exception {
        UUID buyer = user(), seller = user(), admin = user(), listing = UUID.randomUUID(), order = UUID.randomUUID(), payment = UUID.randomUUID(), dispute = UUID.randomUUID();
        insertOrder(buyer, seller, listing, order, "DISPUTED");
        jdbc.update("INSERT INTO payment_order (id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status,created_at,updated_at) VALUES (?,?,?,?,100,100,?,'SUCCEEDED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", payment.toString(), order.toString(), "simulated", "pay-" + payment, "sim-pay-" + payment);
        insertHardCase(dispute, order, buyer, admin);
        claim(dispute, "HARD_DEADLINE");
        CountDownLatch orderLocked = new CountDownLatch(1);
        CountDownLatch allowOrdinary = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var ordinary = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                lockOrder(order, orderLocked);
                awaitBarrier(allowOrdinary);
                return disputes.decide(dispute, admin, "reject-race-" + dispute,
                    com.example.campusmarket.dispute.domain.DisputeDecision.REJECT, 0, "reject".getBytes());
            }));
            assertThat(orderLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var hard = pool.submit(() -> deadlines.runOne(dispute));
            awaitOrderLockWait(order);
            allowOrdinary.countDown();
            assertThat(ordinary.get(20, TimeUnit.SECONDS)).isNotNull();
            assertThat(hard.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            allowOrdinary.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM dispute_case WHERE id=?", String.class, dispute.toString())).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_case WHERE dispute_case_id=?", Integer.class, dispute.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_order WHERE order_id=?", Integer.class, order.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT quarantined_quantity FROM listing WHERE id=?", Integer.class, listing.toString())).isZero();
    }

    @Test
    void sellerResponseWinningAgainstSellerTimeoutDoesNotRearmAdminClaims() throws Exception {
        UUID buyer = user(), seller = user(), listing = UUID.randomUUID(), order = UUID.randomUUID();
        insertAfterSaleOrder(buyer, seller, listing, order);
        var opened = disputes.open(order, buyer, "seller-timeout-race-" + order, 1, "FUNCTIONAL_DEFECT", "race".getBytes());
        UUID dispute = opened.disputeId();
        jdbc.update("UPDATE dispute_case SET seller_deadline=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", dispute.toString());
        jdbc.update("UPDATE dispute_deadline_claim SET due_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE dispute_case_id=? AND deadline_type='SELLER_RESPONSE'", dispute.toString());
        CountDownLatch caseLocked = new CountDownLatch(1);
        CountDownLatch allowResponse = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var response = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                lockCase(dispute, caseLocked);
                awaitBarrier(allowResponse);
                return disputes.respond(dispute, seller, "seller-response-race-" + dispute, "已响应", "response".getBytes());
            }));
            assertThat(caseLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var timeout = pool.submit(() -> deadlines.runOne(dispute));
            awaitCaseLockWait(dispute);
            allowResponse.countDown();
            assertThat(response.get(20, TimeUnit.SECONDS)).isNotNull();
            assertThat(timeout.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            allowResponse.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM dispute_case WHERE id=?", String.class, dispute.toString())).isEqualTo("SELLER_RESPONDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispute_deadline_claim WHERE dispute_case_id=? AND deadline_type IN ('ADMIN_SLA','HARD_DEADLINE')", Integer.class, dispute.toString())).isZero();
    }

    @Test
    void normalOpenRearmsAdminClaimsAfterSeller72HoursAndAlertsOnlyAtRealSla() {
        UUID buyer = user(), seller = user(), listing = UUID.randomUUID(), order = UUID.randomUUID();
        insertAfterSaleOrder(buyer, seller, listing, order);
        var opened = disputes.open(order, buyer, "matrix-open-" + order, 1, "FUNCTIONAL_DEFECT", "matrix".getBytes());
        UUID dispute = opened.disputeId();
        jdbc.update("UPDATE dispute_deadline_claim SET due_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE dispute_case_id=? AND deadline_type='SELLER_RESPONSE'", dispute.toString());
        assertThat(deadlines.runOne(dispute)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM dispute_case WHERE id=?", String.class, dispute.toString())).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT cl.status='NEW' AND ABS(TIMESTAMPDIFF(MICROSECOND,cl.due_at,c.seller_deadline)) < 1000000 FROM dispute_deadline_claim cl JOIN dispute_case c ON c.id=cl.dispute_case_id WHERE cl.dispute_case_id=? AND cl.deadline_type='SELLER_RESPONSE'", Boolean.class, dispute.toString())).isTrue();
        jdbc.update("UPDATE dispute_case SET seller_deadline=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", dispute.toString());
        jdbc.update("UPDATE dispute_deadline_claim SET due_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE dispute_case_id=? AND deadline_type='SELLER_RESPONSE'", dispute.toString());
        assertThat(deadlines.runOne(dispute)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM dispute_case WHERE id=?", String.class, dispute.toString())).isEqualTo("UNDER_REVIEW");
        assertThat(jdbc.queryForObject("SELECT due_at > DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 6 DAY) FROM dispute_deadline_claim WHERE dispute_case_id=? AND deadline_type='ADMIN_SLA'", Boolean.class, dispute.toString())).isTrue();
        assertThat(jdbc.queryForObject("SELECT due_at > DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 13 DAY) FROM dispute_deadline_claim WHERE dispute_case_id=? AND deadline_type='HARD_DEADLINE'", Boolean.class, dispute.toString())).isTrue();

        jdbc.update("UPDATE dispute_deadline_claim SET due_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE dispute_case_id=? AND deadline_type='ADMIN_SLA'", dispute.toString());
        assertThat(deadlines.runOne(dispute)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM dispute_deadline_claim WHERE dispute_case_id=? AND deadline_type='ADMIN_SLA'", String.class, dispute.toString())).isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT ABS(TIMESTAMPDIFF(MICROSECOND,cl.due_at,c.admin_deadline)) < 1000000 FROM dispute_deadline_claim cl JOIN dispute_case c ON c.id=cl.dispute_case_id WHERE cl.dispute_case_id=? AND cl.deadline_type='ADMIN_SLA'", Boolean.class, dispute.toString())).isTrue();
        jdbc.update("UPDATE dispute_case SET admin_deadline=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", dispute.toString());
        jdbc.update("UPDATE dispute_deadline_claim SET due_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE dispute_case_id=? AND deadline_type='ADMIN_SLA'", dispute.toString());
        assertThat(deadlines.runOne(dispute)).isEqualTo(1);
        assertThat(deadlines.runOne(dispute)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='DISPUTE_SLA_ALERT' AND payload->>'$.orderId'=?", Integer.class, order.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='DISPUTE_SLA_ALERT' AND aggregate_id=?", Integer.class, order.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='DISPUTE_SLA_ALERT' AND payload->>'$.disputeCaseId'=?", Integer.class, dispute.toString())).isEqualTo(1);
    }

    @Test
    void deadlineClaimOnlyChangesItsOwnCaseWhenOrderHasTwoCases() {
        UUID buyer = user(), seller = user(), listing = UUID.randomUUID(), order = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        insertOrder(buyer, seller, listing, order, "DISPUTED");
        insertOpenCase(first, order, buyer);
        insertOpenCase(second, order, buyer);
        claim(first, "SELLER_RESPONSE");
        claim(second, "SELLER_RESPONSE");
        assertThat(deadlines.runOne(first)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM dispute_case WHERE id=?", String.class, first.toString())).isEqualTo("UNDER_REVIEW");
        assertThat(jdbc.queryForObject("SELECT status FROM dispute_case WHERE id=?", String.class, second.toString())).isEqualTo("OPEN");
    }

    @Test
    void sellerSilenceMovesCaseToAdminReviewWithoutBlockingLaterDecision() {
        UUID buyer = user(), seller = user(), listing = UUID.randomUUID(), order = UUID.randomUUID(), dispute = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString(), "教材", "描述", "教材");
        jdbc.update("INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,t0,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,100,'DISPUTED',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", order.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "描述");
        jdbc.update("INSERT INTO dispute_case (id,order_id,initiator_id,disputed_quantity,reason,status,seller_deadline,admin_deadline,hard_deadline,version,opened_at,created_at,updated_at) VALUES (?,?,?,1,'QUANTITY','OPEN',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 7 DAY),DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 14 DAY),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", dispute.toString(), order.toString(), buyer.toString());
        claim(dispute, "SELLER_RESPONSE");

        assertThat(deadlines.runOne(dispute)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM dispute_case WHERE id=?", String.class, dispute.toString())).isEqualTo("UNDER_REVIEW");
        assertThat(jdbc.queryForObject("SELECT status FROM dispute_deadline_claim WHERE dispute_case_id=? AND deadline_type='SELLER_RESPONSE'", String.class, dispute.toString())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT due_at > CURRENT_TIMESTAMP(6) FROM dispute_deadline_claim WHERE dispute_case_id=? AND deadline_type='ADMIN_SLA'", Boolean.class, dispute.toString())).isTrue();
        assertThat(jdbc.queryForObject("SELECT due_at > DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 13 DAY) FROM dispute_deadline_claim WHERE dispute_case_id=? AND deadline_type='HARD_DEADLINE'", Boolean.class, dispute.toString())).isTrue();
    }

    @Test
    void expiredProcessingClaimIsTakenOverWithFreshToken() {
        UUID buyer = user(), seller = user(), listing = UUID.randomUUID(), order = UUID.randomUUID(), dispute = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString(), "教材", "描述", "教材");
        jdbc.update("INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,t0,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,100,'DISPUTED',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", order.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "描述");
        jdbc.update("INSERT INTO dispute_case (id,order_id,initiator_id,disputed_quantity,reason,status,seller_deadline,admin_deadline,hard_deadline,version,opened_at,created_at,updated_at) VALUES (?,?,?,1,'QUANTITY','UNDER_REVIEW',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 14 DAY),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", dispute.toString(), order.toString(), buyer.toString());
        jdbc.update("INSERT INTO dispute_deadline_claim (id,dispute_case_id,deadline_type,due_at,status,owner_id,claim_token,lease_until,created_at,updated_at) VALUES (?,?, 'ADMIN_SLA',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),'PROCESSING','old-owner','old-token',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", UUID.randomUUID().toString(), dispute.toString());

        assertThat(deadlines.runOne(dispute)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM dispute_deadline_claim WHERE dispute_case_id=? AND deadline_type='ADMIN_SLA'", String.class, dispute.toString())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT owner_id FROM dispute_deadline_claim WHERE dispute_case_id=? AND deadline_type='ADMIN_SLA'", String.class, dispute.toString())).isNull();
        assertThat(jdbc.update("UPDATE dispute_deadline_claim SET status='COMPLETED' WHERE dispute_case_id=? AND deadline_type='ADMIN_SLA' AND owner_id='old-owner' AND claim_token='old-token'", dispute.toString())).isZero();
    }

    @Test
    void hardDeadlineWithoutTrustedProofEscalatesAndFreezesFunds() {
        UUID buyer = user(), seller = user(), listing = UUID.randomUUID(), order = UUID.randomUUID(), dispute = UUID.randomUUID();
        insertOrder(buyer, seller, listing, order, "DISPUTED");
        jdbc.update("INSERT INTO dispute_case (id,order_id,initiator_id,disputed_quantity,reason,status,proof_type,seller_deadline,admin_deadline,hard_deadline,version,opened_at,created_at,updated_at) VALUES (?,?,?,1,'QUANTITY','UNDER_REVIEW','ADMIN_CONFIRMED',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 3 DAY),DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", dispute.toString(), order.toString(), buyer.toString());
        claim(dispute, "HARD_DEADLINE");

        assertThat(deadlines.runOne(dispute)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM dispute_case WHERE id=?", String.class, dispute.toString())).isEqualTo("ESCALATED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_order WHERE order_id=?", Integer.class, order.toString())).isZero();
    }

    @Test
    void trustedHardDeadlineCreatesOneRefundOutboxAndSlaAlertIsOneShot() {
        UUID buyer = user(), seller = user(), listing = UUID.randomUUID(), order = UUID.randomUUID(), payment = UUID.randomUUID(), dispute = UUID.randomUUID();
        UUID admin = user();
        insertOrder(buyer, seller, listing, order, "DISPUTED");
        jdbc.update("INSERT INTO payment_order (id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status,created_at,updated_at) VALUES (?,?,?,?,100,100,?,'SUCCEEDED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", payment.toString(), order.toString(), "simulated", "pay-" + payment, "sim-pay-" + payment);
        jdbc.update("INSERT INTO dispute_case (id,order_id,initiator_id,assigned_admin_id,disputed_quantity,reason,status,proof_type,proof_reference,seller_deadline,admin_deadline,hard_deadline,version,opened_at,created_at,updated_at) VALUES (?,?,?, ?,1,'QUANTITY','UNDER_REVIEW','ADMIN_CONFIRMED',?,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 3 DAY),DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", dispute.toString(), order.toString(), buyer.toString(), admin.toString(), admin.toString());
        claim(dispute, "HARD_DEADLINE");
        assertThat(deadlines.runOne(dispute)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM dispute_case WHERE id=?", String.class, dispute.toString())).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='REFUND_REQUESTED' AND aggregate_id IN (SELECT id FROM refund_order WHERE order_id=?)", Integer.class, order.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source_id FROM refund_order WHERE order_id=?", String.class, order.toString())).isEqualTo(dispute.toString());
        UUID refund = UUID.fromString(jdbc.queryForObject("SELECT id FROM refund_order WHERE order_id=?", String.class, order.toString()));
        provider.setRefundStatus(refunds.queryRefund(refund).providerReference(), "SUCCEEDED");
        refunds.reconcileRefund(refund);
        returns.reconcileSuccessfulRefund(refund);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_case WHERE dispute_case_id=? AND refund_id=? AND resolution_type='RETURN_AND_REFUND'", Integer.class, dispute.toString(), refund.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT quarantined_quantity FROM listing WHERE id=?", Integer.class, listing.toString())).isEqualTo(1);

        UUID slaCase = UUID.randomUUID();
        jdbc.update("INSERT INTO dispute_case (id,order_id,initiator_id,disputed_quantity,reason,status,admin_deadline,hard_deadline,version,opened_at,created_at,updated_at) VALUES (?,?,?,1,'QUANTITY','UNDER_REVIEW',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", slaCase.toString(), order.toString(), buyer.toString());
        claim(slaCase, "ADMIN_SLA");
        assertThat(deadlines.runOne(slaCase)).isEqualTo(1);
        assertThat(deadlines.runOne(slaCase)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='DISPUTE_SLA_ALERT' AND aggregate_id=?", Integer.class, order.toString())).isEqualTo(1);
    }

    @Test
    void failedHardDeadlineRefundEscalatesAndControlledRecoveryConverges() {
        UUID buyer = user(), seller = user(), admin = user(), listing = UUID.randomUUID(), order = UUID.randomUUID(), payment = UUID.randomUUID(), dispute = UUID.randomUUID();
        insertOrder(buyer, seller, listing, order, "DISPUTED");
        jdbc.update("INSERT INTO payment_order (id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status,created_at,updated_at) VALUES (?,?,?,?,100,100,?,'SUCCEEDED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", payment.toString(), order.toString(), "simulated", "pay-" + payment, "sim-pay-" + payment);
        jdbc.update("INSERT INTO dispute_case (id,order_id,initiator_id,assigned_admin_id,disputed_quantity,reason,status,proof_type,proof_reference,seller_deadline,admin_deadline,hard_deadline,version,opened_at,created_at,updated_at) VALUES (?,?,?, ?,1,'QUANTITY','UNDER_REVIEW','ADMIN_CONFIRMED',?,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 3 DAY),DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", dispute.toString(), order.toString(), buyer.toString(), admin.toString(), admin.toString());
        claim(dispute, "HARD_DEADLINE");
        provider.setNextRefundStatusForTest("FAILED");

        assertThat(deadlines.runOne(dispute)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM dispute_case WHERE id=?", String.class, dispute.toString())).isEqualTo("ESCALATED");
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("DISPUTED");
        assertThat(jdbc.queryForObject("SELECT refund_status FROM return_case WHERE dispute_case_id=?", String.class, dispute.toString())).isEqualTo("FAILED");
        String reference = jdbc.queryForObject("SELECT provider_reference FROM refund_order WHERE order_id=?", String.class, order.toString());
        provider.setRefundStatus(reference, "SUCCEEDED");

        assertThat(returns.retryHardDeadlineRefund(dispute).refundStatus()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM dispute_case WHERE id=?", String.class, dispute.toString())).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("REFUNDED");
    }

    @Test
    void concurrentHardDeadlinesReserveAcrossCasesWithoutExceedingOrderQuantity() throws Exception {
        UUID buyer = user(), seller = user(), admin = user(), listing = UUID.randomUUID(), order = UUID.randomUUID();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), payment = UUID.randomUUID();
        insertOrder(buyer, seller, listing, order, "DISPUTED");
        jdbc.update("UPDATE trade_order SET quantity=1,total_amount_fen=100,paid_amount_fen=100 WHERE id=?", order.toString());
        jdbc.update("INSERT INTO payment_order (id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status,created_at,updated_at) VALUES (?,?,?,?,100,100,?,'SUCCEEDED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            payment.toString(), order.toString(), "simulated", "pay-" + payment, "sim-pay-" + payment);
        insertHardCase(first, order, buyer, admin);
        insertHardCase(second, order, buyer, admin);
        claim(first, "HARD_DEADLINE");
        claim(second, "HARD_DEADLINE");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> deadlines.runOne(first));
            var two = pool.submit(() -> deadlines.runOne(second));
            assertThat(one.get(20, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(two.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_case WHERE order_id=?", Integer.class, order.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_order WHERE order_id=?", Integer.class, order.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispute_case WHERE order_id=? AND status='RESOLVED'", Integer.class, order.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispute_case WHERE order_id=? AND status='ESCALATED'", Integer.class, order.toString())).isEqualTo(1);
    }

    private void insertHardCase(UUID dispute, UUID order, UUID buyer, UUID admin) {
        jdbc.update("INSERT INTO dispute_case (id,order_id,initiator_id,assigned_admin_id,disputed_quantity,reason,status,proof_type,proof_reference,seller_deadline,admin_deadline,hard_deadline,version,opened_at,created_at,updated_at) VALUES (?,?,?, ?,1,'QUANTITY','UNDER_REVIEW','ADMIN_CONFIRMED',?,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 3 DAY),DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            dispute.toString(), order.toString(), buyer.toString(), admin.toString(), admin.toString());
    }

    private void lockOrder(UUID order, CountDownLatch locked) {
        jdbc.queryForObject("SELECT id FROM trade_order WHERE id=? FOR UPDATE", String.class, order.toString());
        locked.countDown();
    }

    private void lockCase(UUID dispute, CountDownLatch locked) {
        jdbc.queryForObject("SELECT id FROM dispute_case WHERE id=? FOR UPDATE", String.class, dispute.toString());
        locked.countDown();
    }

    private void awaitOrderLockWait(UUID order) {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(lockObserver.queryForObject("""
                SELECT COUNT(*)
                FROM performance_schema.data_lock_waits w
                JOIN performance_schema.data_locks requesting
                  ON requesting.ENGINE='INNODB'
                 AND requesting.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                 AND requesting.ENGINE_TRANSACTION_ID=w.REQUESTING_ENGINE_TRANSACTION_ID
                JOIN performance_schema.data_locks blocking
                  ON blocking.ENGINE='INNODB'
                 AND blocking.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                 AND blocking.ENGINE_TRANSACTION_ID=w.BLOCKING_ENGINE_TRANSACTION_ID
                WHERE requesting.OBJECT_SCHEMA=DATABASE()
                  AND requesting.OBJECT_NAME='trade_order'
                  AND requesting.LOCK_STATUS='WAITING'
                  AND blocking.OBJECT_SCHEMA=DATABASE()
                  AND blocking.OBJECT_NAME='trade_order'
                  AND blocking.LOCK_STATUS='GRANTED'
                  AND requesting.INDEX_NAME IN ('PRIMARY','PRIMARY KEY')
                  AND blocking.INDEX_NAME IN ('PRIMARY','PRIMARY KEY')""", Long.class)).isGreaterThan(0L));
    }

    private void awaitCaseLockWait(UUID dispute) {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(lockObserver.queryForObject("""
                SELECT COUNT(*)
                FROM performance_schema.data_lock_waits w
                JOIN performance_schema.data_locks requesting
                  ON requesting.ENGINE='INNODB'
                 AND requesting.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                 AND requesting.ENGINE_TRANSACTION_ID=w.REQUESTING_ENGINE_TRANSACTION_ID
                JOIN performance_schema.data_locks blocking
                  ON blocking.ENGINE='INNODB'
                 AND blocking.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                 AND blocking.ENGINE_TRANSACTION_ID=w.BLOCKING_ENGINE_TRANSACTION_ID
                WHERE requesting.OBJECT_SCHEMA=DATABASE()
                  AND requesting.OBJECT_NAME='dispute_case'
                  AND requesting.LOCK_STATUS='WAITING'
                  AND blocking.OBJECT_SCHEMA=DATABASE()
                  AND blocking.OBJECT_NAME='dispute_case'
                  AND blocking.LOCK_STATUS='GRANTED'
                  AND requesting.INDEX_NAME IN ('PRIMARY','PRIMARY KEY')
                  AND blocking.INDEX_NAME IN ('PRIMARY','PRIMARY KEY')""", Long.class)).isGreaterThan(0L));
    }

    private static void awaitBarrier(CountDownLatch barrier) {
        try {
            if (!barrier.await(10, TimeUnit.SECONDS)) throw new AssertionError("并发测试栅栏超时");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("并发测试线程被中断", interrupted);
        }
    }

    private void insertOrder(UUID buyer, UUID seller, UUID listing, UUID order, String status) {
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString(), "教材", "描述", "教材");
        jdbc.update("INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,t0,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,100,?,0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", order.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "描述", status);
    }

    private void insertAfterSaleOrder(UUID buyer, UUID seller, UUID listing, UUID order) {
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString(), "教材", "描述", "教材");
        jdbc.update("INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,t0,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,100,'AFTERSALE_WINDOW',0,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", order.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "描述");
    }

    private void insertOpenCase(UUID dispute, UUID order, UUID buyer) {
        jdbc.update("INSERT INTO dispute_case (id,order_id,initiator_id,disputed_quantity,reason,status,seller_deadline,version,opened_at,created_at,updated_at) VALUES (?,?,?,1,'QUANTITY','OPEN',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", dispute.toString(), order.toString(), buyer.toString());
    }

    private void claim(UUID dispute, String type) {
        jdbc.update("INSERT INTO dispute_deadline_claim (id,dispute_case_id,deadline_type,due_at,status,created_at,updated_at) VALUES (?,?,?,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),'NEW',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", UUID.randomUUID().toString(), dispute.toString(), type);
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_user (id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", id.toString(), id + "@stu.example.edu.cn", "hash");
        return id;
    }
}
