package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.catalog.application.InventoryPort;
import com.example.campusmarket.dispute.application.ReturnResolutionService;
import com.example.campusmarket.dispute.application.ProofAuthority;
import com.example.campusmarket.dispute.application.ReturnProofAttestationService;
import com.example.campusmarket.dispute.application.ReturnResolutionScheduler;
import com.example.campusmarket.dispute.domain.DisputeDecision;
import com.example.campusmarket.dispute.domain.ReturnProofType;
import com.example.campusmarket.payment.application.SettlementService;
import com.example.campusmarket.payment.application.RefundService;
import com.example.campusmarket.payment.infrastructure.SimulatedPaymentProviderController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** 真实 MySQL：三件退一件只隔离一件，剩余金额可在七天后净结算。 */
@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {"server.port=18082", "campus.market.payment.provider-url=http://localhost:18082/simulated-provider",
    "campus.market.search.dispatcher.enabled=false", "campus.market.dispute.deadline.enabled=false",
    "campus.market.dispute.return-reconciliation.enabled=true",
    "campus.market.dispute.return-reconciliation.initial-delay-ms=86400000",
    "campus.market.dispute.return-reconciliation.fixed-delay-ms=86400000"})
class PartialReturnRefundIT extends Task11MySqlContainers {
    @Autowired JdbcTemplate jdbc;
    /** root 连接只读取 MySQL Performance Schema，确认 settlement 线程确实在等待被测行锁。 */
    private final JdbcTemplate lockObserver = new JdbcTemplate(
        new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
    @Autowired ReturnResolutionService returns;
    @Autowired ReturnProofAttestationService attestations;
    @Autowired ReturnResolutionScheduler reconciliation;
    @Autowired SettlementService settlements;
    @Autowired RefundService refunds;
    @Autowired SimulatedPaymentProviderController provider;
    @Autowired InventoryPort inventory;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void returnsOneOfThreeQuarantinesOnlyOneAndSettlesTheRemainingNetAmount() {
        UUID buyer = user(), seller = user(), listing = UUID.randomUUID(), order = UUID.randomUUID(), payment = UUID.randomUUID(), dispute = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString(), "教材", "描述", "教材");
        jdbc.update("INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,t0,created_at,updated_at) VALUES (?,?,?,?,?,?,100,3,300,300,'DISPUTED',0,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 8 DAY),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", order.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "描述");
        jdbc.update("INSERT INTO payment_order (id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status,created_at,updated_at) VALUES (?,?,?,?,300,300,?,'SUCCEEDED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", payment.toString(), order.toString(), "simulated", "pay-" + payment, "sim-pay-" + payment);
        jdbc.update("INSERT INTO dispute_case (id,order_id,initiator_id,disputed_quantity,reason,status,seller_deadline,hard_deadline,version,opened_at,created_at,updated_at) VALUES (?,?,?,1,'QUANTITY','UNDER_REVIEW',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),0, CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", dispute.toString(), order.toString(), buyer.toString());

        var result = returns.resolve(dispute, DisputeDecision.RETURN_AND_REFUND, 1, ReturnProofType.SELLER_CONFIRMED, "seller-confirmed", ProofAuthority.seller(seller));
        assertThat(result.refundStatus()).isEqualTo("PROCESSING");
        assertThat(result.amountFen()).isEqualTo(100L);
        provider.setRefundStatus(refunds.queryRefund(result.refundId()).providerReference(), "SUCCEEDED");
        assertThat(refunds.reconcileRefund(result.refundId()).status()).isEqualTo("SUCCEEDED");
        assertThat(returns.reconcileSuccessfulRefund(result.refundId()).refundStatus()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT quarantined_quantity FROM listing WHERE id=?", Integer.class, listing.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT available_quantity FROM listing WHERE id=?", Integer.class, listing.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("AFTERSALE_WINDOW");

        var settlement = settlements.settle(order);
        assertThat(settlement.status()).isEqualTo("SETTLED");
        assertThat(settlement.netSettlementFen()).isEqualTo(200L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='SETTLEMENT_CREATED' AND aggregate_id=?", Integer.class, order.toString())).isEqualTo(1);
    }

    @Test
    void successfulRefundReconcilesWhenProcessCrashesAfterPrepare() {
        Fixture f = fixture(1, 100);
        var result = returns.resolve(f.dispute(), DisputeDecision.RETURN_AND_REFUND, 1, ReturnProofType.SELLER_CONFIRMED, "seller-confirmed", ProofAuthority.seller(f.seller()));
        jdbc.update("UPDATE return_case SET refund_id=NULL,refund_status='PROCESSING' WHERE dispute_case_id=?", f.dispute().toString());
        provider.setRefundStatus(refunds.queryRefund(result.refundId()).providerReference(), "SUCCEEDED");
        refunds.reconcileRefund(result.refundId());
        assertThat(returns.reconcileSuccessfulRefund(result.refundId()).refundStatus()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT refund_id FROM return_case WHERE dispute_case_id=?", String.class, f.dispute().toString())).isEqualTo(result.refundId().toString());
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, f.order().toString())).isEqualTo("REFUNDED");
    }

    @Test
    void preparedReturnWithoutRefundRowIsRecoveredByOriginalIdempotencyKey() {
        Fixture f = fixture(1, 100);
        UUID returnCase = UUID.nameUUIDFromBytes((f.dispute() + "|REFUND_ONLY").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("UPDATE dispute_case SET status='RESOLVED',decision='REFUND_ONLY',approved_quantity=1 WHERE id=?", f.dispute().toString());
        jdbc.update("INSERT INTO return_case (id,dispute_case_id,order_id,listing_id,payment_order_id,unit_price_fen,status,proof_type,proof_reference,resolution_type,approved_quantity,deadline,created_at,updated_at) VALUES (?,?,?,?,?,100,'CONFIRMED','SELLER_CONFIRMED','seller-confirmed','REFUND_ONLY',1,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 14 DAY),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            returnCase.toString(), f.dispute().toString(), f.order().toString(), f.listing().toString(), f.payment().toString());

        assertThat(returns.recoverPendingPreparedReturns(10)).isEqualTo(1);
        String refundId = jdbc.queryForObject("SELECT refund_id FROM return_case WHERE dispute_case_id=?", String.class, f.dispute().toString());
        assertThat(refundId).isNotNull();
        assertThat(jdbc.queryForObject("SELECT idempotency_key FROM refund_order WHERE id=?", String.class, refundId))
            .isEqualTo("dispute-return-" + f.dispute());
    }

    @Test
    void resolvedReturnRetryReadsExistingRefundAndDoesNotOverwriteProofMetadata() {
        Fixture f = fixture(1, 100);
        var first = returns.resolve(f.dispute(), DisputeDecision.RETURN_AND_REFUND, 1,
            ReturnProofType.SELLER_CONFIRMED, "seller-confirmed-original", ProofAuthority.seller(f.seller()));

        var second = returns.resolve(f.dispute(), DisputeDecision.RETURN_AND_REFUND, 1,
            ReturnProofType.SELLER_CONFIRMED, "seller-confirmed-late-retry", ProofAuthority.seller(f.seller()));

        assertThat(second.refundId()).isEqualTo(first.refundId());
        assertThat(second.refundStatus()).isEqualTo(first.refundStatus());
        assertThat(jdbc.queryForObject("SELECT proof_reference FROM return_case WHERE dispute_case_id=?", String.class,
            f.dispute().toString())).isEqualTo("seller-confirmed-original");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_order WHERE order_id=?", Integer.class,
            f.order().toString())).isEqualTo(1);
    }

    @Test
    void preparedReturnWithoutRefundIdKeepsOriginalProofWhenExternalCallRecoveryReplays() {
        Fixture f = fixture(1, 100);
        var first = returns.resolve(f.dispute(), DisputeDecision.RETURN_AND_REFUND, 1,
            ReturnProofType.SELLER_CONFIRMED, "seller-confirmed-original", ProofAuthority.seller(f.seller()));
        jdbc.update("UPDATE return_case SET refund_id=NULL,refund_status='PROCESSING' WHERE dispute_case_id=?", f.dispute().toString());

        var recovered = returns.resolve(f.dispute(), DisputeDecision.REFUND_ONLY, 1,
            ReturnProofType.SELLER_CONFIRMED, "late-proof-must-not-replace", ProofAuthority.seller(f.seller()));

        assertThat(recovered.refundId()).isEqualTo(first.refundId());
        assertThat(jdbc.queryForObject("SELECT proof_reference FROM return_case WHERE dispute_case_id=?", String.class,
            f.dispute().toString())).isEqualTo("seller-confirmed-original");
        assertThat(jdbc.queryForObject("SELECT resolution_type FROM return_case WHERE dispute_case_id=?", String.class,
            f.dispute().toString())).isEqualTo("RETURN_AND_REFUND");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_order WHERE order_id=?", Integer.class,
            f.order().toString())).isEqualTo(1);
    }

    @Test
    void recoveryScanLinksFailedRefundAndEscalatesWhileKeepingRefundId() {
        Fixture f = fixture(1, 100);
        UUID refund = UUID.randomUUID();
        UUID returnCase = UUID.nameUUIDFromBytes((f.dispute() + "|REFUND_ONLY").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("UPDATE dispute_case SET status='RESOLVED',decision='REFUND_ONLY',approved_quantity=1 WHERE id=?", f.dispute().toString());
        jdbc.update("INSERT INTO refund_order (id,order_id,payment_order_id,provider,idempotency_key,source_type,source_id,paid_amount_fen,amount_fen,provider_reference,status,created_at,updated_at) VALUES (?,?,?,?,?,'DISPUTE',?,?,?,?,'FAILED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            refund.toString(), f.order().toString(), f.payment().toString(), "simulated", "dispute-return-" + f.dispute(), f.dispute().toString(), 100, 100, "failed-" + refund);
        jdbc.update("INSERT INTO return_case (id,dispute_case_id,order_id,listing_id,payment_order_id,unit_price_fen,status,proof_type,proof_reference,resolution_type,approved_quantity,deadline,created_at,updated_at) VALUES (?,?,?,?,?,100,'CONFIRMED','SELLER_CONFIRMED','seller-confirmed','REFUND_ONLY',1,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 14 DAY),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            returnCase.toString(), f.dispute().toString(), f.order().toString(), f.listing().toString(), f.payment().toString());

        assertThat(returns.recoverPendingPreparedReturns(10)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT refund_id FROM return_case WHERE dispute_case_id=?", String.class, f.dispute().toString())).isEqualTo(refund.toString());
        assertThat(jdbc.queryForObject("SELECT status FROM return_case WHERE dispute_case_id=?", String.class, f.dispute().toString())).isEqualTo("ESCALATED");
        assertThat(jdbc.queryForObject("SELECT status FROM dispute_case WHERE id=?", String.class, f.dispute().toString())).isEqualTo("ESCALATED");
    }

    @Test
    void escalatedFailedRefundStillReservesQuantityForAnotherDecision() {
        Fixture f = fixture(1, 100);
        provider.setNextRefundStatusForTest("FAILED");
        var failed = returns.resolve(f.dispute(), DisputeDecision.REFUND_ONLY, 1,
            ReturnProofType.SELLER_CONFIRMED, "seller-confirmed", ProofAuthority.seller(f.seller()));
        UUID secondDispute = UUID.randomUUID();
        jdbc.update("INSERT INTO dispute_case (id,order_id,initiator_id,disputed_quantity,reason,status,seller_deadline,hard_deadline,version,opened_at,created_at,updated_at) VALUES (?,?,?,1,'QUANTITY','UNDER_REVIEW',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            secondDispute.toString(), f.order().toString(), f.buyer().toString());

        assertThatThrownBy(() -> returns.resolve(secondDispute, DisputeDecision.REFUND_ONLY, 1,
            ReturnProofType.SELLER_CONFIRMED, "seller-confirmed-2", ProofAuthority.seller(f.seller())))
            .isInstanceOf(IllegalArgumentException.class);
        provider.setRefundStatus(refunds.queryRefund(failed.refundId()).providerReference(), "SUCCEEDED");
        assertThat(returns.retryFailedRefund(f.dispute()).refundStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    void directSuccessfulReturnRefundPersistsQuarantineAndSchedulerSecondRunIsIdle() {
        Fixture f = fixture(1, 100);
        provider.setNextRefundStatusForTest("SUCCEEDED");
        var result = returns.resolve(f.dispute(), DisputeDecision.RETURN_AND_REFUND, 1,
            ReturnProofType.SELLER_CONFIRMED, "seller-confirmed", ProofAuthority.seller(f.seller()));
        assertThat(result.refundStatus()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT quarantined_at IS NOT NULL FROM return_case WHERE dispute_case_id=?", Boolean.class, f.dispute().toString())).isTrue();
        assertThat(reconciliation.runOnce(50)).isZero();
        assertThat(jdbc.queryForObject("SELECT quarantined_at IS NOT NULL FROM return_case WHERE dispute_case_id=?", Boolean.class, f.dispute().toString())).isTrue();
    }

    @Test
    void ordinaryFailedRefundEscalatesAndReusesSameRefundOnControlledRecovery() {
        Fixture f = fixture(1, 100);
        provider.setNextRefundStatusForTest("FAILED");
        var failed = returns.resolve(f.dispute(), DisputeDecision.REFUND_ONLY, 1,
            ReturnProofType.SELLER_CONFIRMED, "seller-confirmed", ProofAuthority.seller(f.seller()));
        assertThat(failed.refundStatus()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM dispute_case WHERE id=?", String.class, f.dispute().toString())).isEqualTo("ESCALATED");
        assertThat(jdbc.queryForObject("SELECT status FROM return_case WHERE dispute_case_id=?", String.class, f.dispute().toString())).isEqualTo("ESCALATED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_order WHERE order_id=?", Integer.class, f.order().toString())).isEqualTo(1);
        String providerReference = refunds.queryRefund(failed.refundId()).providerReference();
        assertThat(returns.retryFailedRefund(f.dispute()).refundStatus()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT reserved_refund_fen FROM payment_order WHERE id=?", Long.class, f.payment().toString())).isZero();
        provider.setRefundStatus(providerReference, "UNKNOWN");
        assertThat(returns.retryFailedRefund(f.dispute()).refundStatus()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT reserved_refund_fen FROM payment_order WHERE id=?", Long.class, f.payment().toString())).isZero();
        provider.setRefundStatus(providerReference, "SUCCEEDED");
        var recovered = returns.retryFailedRefund(f.dispute());
        assertThat(recovered.refundId()).isEqualTo(failed.refundId());
        assertThat(recovered.refundStatus()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_order WHERE order_id=?", Integer.class, f.order().toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, f.order().toString())).isEqualTo("REFUNDED");
    }

    @Test
    void successfulRefundBeforeReturnReconciliationBlocksSettlementUntilOrderConverges() {
        Fixture f = fixture(1, 100);
        var result = returns.resolve(f.dispute(), DisputeDecision.RETURN_AND_REFUND, 1,
            ReturnProofType.SELLER_CONFIRMED, "seller-confirmed", ProofAuthority.seller(f.seller()));
        provider.setRefundStatus(refunds.queryRefund(result.refundId()).providerReference(), "SUCCEEDED");
        assertThat(refunds.reconcileRefund(result.refundId()).status()).isEqualTo("SUCCEEDED");
        jdbc.update("UPDATE trade_order SET status='AFTERSALE_WINDOW' WHERE id=?", f.order().toString());

        var blocked = settlements.settle(f.order());
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.blockedReason()).isEqualTo("UNRECONCILED_RETURN");
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, f.order().toString())).isEqualTo("AFTERSALE_WINDOW");

        assertThat(returns.reconcileSuccessfulRefund(result.refundId()).refundStatus()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, f.order().toString())).isEqualTo("REFUNDED");
    }

    @Test
    void refundOnlySuccessDoesNotTouchQuarantine() {
        Fixture f = fixture(1, 100);
        java.time.Instant verifiedAt = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", java.sql.Timestamp.class).toInstant().minusSeconds(1);
        attestations.recordDelivered(f.order(), "simulated", "provider-delivered", 1, verifiedAt);
        var result = returns.resolve(f.dispute(), DisputeDecision.REFUND_ONLY, 1, ReturnProofType.PROVIDER_DELIVERED, "provider-delivered", ProofAuthority.provider("provider-delivered"));
        provider.setRefundStatus(refunds.queryRefund(result.refundId()).providerReference(), "SUCCEEDED");
        refunds.reconcileRefund(result.refundId());
        returns.reconcileSuccessfulRefund(result.refundId());
        assertThat(jdbc.queryForObject("SELECT quarantined_quantity FROM listing WHERE id=?", Integer.class, f.listing().toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, f.order().toString())).isEqualTo("REFUNDED");
    }

    @Test
    void buyerEvidenceCannotTriggerRefund() {
        Fixture f = fixture(1, 100);
        assertThatThrownBy(() -> returns.resolve(f.dispute(), DisputeDecision.REFUND_ONLY, 1, ReturnProofType.BUYER_EVIDENCE, "buyer-video", ProofAuthority.provider("buyer-video")))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_order WHERE order_id=?", Integer.class, f.order().toString())).isZero();
    }

    @Test
    void trustedProofMustHaveAuthorizedSource() {
        Fixture f = fixture(1, 100);
        assertThatThrownBy(() -> returns.resolve(f.dispute(), DisputeDecision.REFUND_ONLY, 1,
            ReturnProofType.SELLER_CONFIRMED, "seller-confirmed", ProofAuthority.seller(UUID.randomUUID())))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> returns.resolve(f.dispute(), DisputeDecision.REFUND_ONLY, 1,
            ReturnProofType.ADMIN_CONFIRMED, "admin-confirmed", ProofAuthority.admin(UUID.randomUUID())))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> returns.resolve(f.dispute(), DisputeDecision.REFUND_ONLY, 1,
            ReturnProofType.PROVIDER_DELIVERED, "fake-provider-reference", ProofAuthority.provider("fake-provider-reference")))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_order WHERE order_id=?", Integer.class, f.order().toString())).isZero();
    }

    @Test
    void providerAttestationMustMatchOrderProviderStatusAndVerifiedTime() {
        Fixture f = fixture(1, 100);
        assertThatThrownBy(() -> attestations.recordDelivered(f.order(), "simulated", "future-boundary", 1, java.time.Instant.now().plusSeconds(60)))
            .isInstanceOf(IllegalArgumentException.class);
        jdbc.update("INSERT INTO return_proof_attestation (id,proof_reference,order_id,provider,delivered_quantity,status,verified_at,created_at) VALUES (?,?,?,?,1,'DELIVERED',DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),CURRENT_TIMESTAMP(6))",
            UUID.randomUUID().toString(), "future-proof", f.order().toString(), "simulated");
        assertThatThrownBy(() -> returns.resolve(f.dispute(), DisputeDecision.REFUND_ONLY, 1, ReturnProofType.PROVIDER_DELIVERED, "future-proof", ProofAuthority.provider("future-proof")))
            .isInstanceOf(IllegalStateException.class);

        jdbc.update("INSERT INTO return_proof_attestation (id,proof_reference,order_id,provider,delivered_quantity,status,verified_at,created_at) VALUES (?,?,?,?,1,'DELIVERED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            UUID.randomUUID().toString(), "wrong-provider", f.order().toString(), "other-provider");
        assertThatThrownBy(() -> returns.resolve(f.dispute(), DisputeDecision.REFUND_ONLY, 1, ReturnProofType.PROVIDER_DELIVERED, "wrong-provider", ProofAuthority.provider("wrong-provider")))
            .isInstanceOf(IllegalStateException.class);

        Fixture other = fixture(1, 100);
        jdbc.update("INSERT INTO return_proof_attestation (id,proof_reference,order_id,provider,delivered_quantity,status,verified_at,created_at) VALUES (?,?,?,?,1,'DELIVERED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            UUID.randomUUID().toString(), "cross-order-proof", other.order().toString(), "simulated");
        assertThatThrownBy(() -> returns.resolve(f.dispute(), DisputeDecision.REFUND_ONLY, 1, ReturnProofType.PROVIDER_DELIVERED, "cross-order-proof", ProofAuthority.provider("cross-order-proof")))
            .isInstanceOf(IllegalStateException.class);

        jdbc.update("INSERT INTO return_proof_attestation (id,proof_reference,order_id,provider,delivered_quantity,status,verified_at,created_at) VALUES (?,?,?,?,1,'REJECTED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            UUID.randomUUID().toString(), "rejected-proof", f.order().toString(), "simulated");
        assertThatThrownBy(() -> returns.resolve(f.dispute(), DisputeDecision.REFUND_ONLY, 1, ReturnProofType.PROVIDER_DELIVERED, "rejected-proof", ProofAuthority.provider("rejected-proof")))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_order WHERE order_id=?", Integer.class, f.order().toString())).isZero();
    }

    @Test
    void unknownRefundAndReservationBlockSettlement() {
        Fixture f = fixture(1, 100);
        jdbc.update("UPDATE trade_order SET status='AFTERSALE_WINDOW' WHERE id=?", f.order().toString());
        jdbc.update("UPDATE payment_order SET reserved_refund_fen=100 WHERE id=?", f.payment().toString());
        UUID refund = UUID.randomUUID();
        jdbc.update("INSERT INTO refund_order (id,order_id,payment_order_id,provider,idempotency_key,source_type,source_id,paid_amount_fen,amount_fen,status,created_at,updated_at) VALUES (?,?,?,?,?,'DISPUTE',?,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            refund.toString(), f.order().toString(), f.payment().toString(), "simulated", "unknown-" + refund, f.dispute().toString(), 100, 100, "UNKNOWN");
        assertThat(settlements.settle(f.order()).status()).isEqualTo("BLOCKED");
    }

    @Test
    void concurrentDecisionsCreateOneRefundAndOneQuarantine() throws Exception {
        Fixture f = fixture(1, 100);
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { ready.countDown(); start.await(5, TimeUnit.SECONDS); return returns.resolve(f.dispute(), DisputeDecision.RETURN_AND_REFUND, 1, ReturnProofType.SELLER_CONFIRMED, "seller-confirmed", ProofAuthority.seller(f.seller())); });
            var second = pool.submit(() -> { ready.countDown(); start.await(5, TimeUnit.SECONDS); return returns.resolve(f.dispute(), DisputeDecision.RETURN_AND_REFUND, 1, ReturnProofType.SELLER_CONFIRMED, "seller-confirmed", ProofAuthority.seller(f.seller())); });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var firstResult = first.get(20, TimeUnit.SECONDS);
            var secondResult = second.get(20, TimeUnit.SECONDS);
            assertThat(firstResult.refundId()).isEqualTo(secondResult.refundId());
            provider.setRefundStatus(refunds.queryRefund(firstResult.refundId()).providerReference(), "SUCCEEDED");
            refunds.reconcileRefund(firstResult.refundId());
            returns.reconcileSuccessfulRefund(firstResult.refundId());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_order WHERE order_id=?", Integer.class, f.order().toString())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT successful_refund_fen FROM payment_order WHERE id=?", Long.class, f.payment().toString())).isEqualTo(100L);
            assertThat(jdbc.queryForObject("SELECT quarantined_quantity FROM listing WHERE id=?", Integer.class, f.listing().toString())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoDifferentDisputesSameOrderCannotExceedQuantityOrPaidAmount() throws Exception {
        Fixture f = fixture(2, 100);
        jdbc.update("UPDATE dispute_case SET disputed_quantity=1 WHERE id=?", f.dispute().toString());
        UUID secondDispute = UUID.randomUUID();
        jdbc.update("INSERT INTO dispute_case (id,order_id,initiator_id,disputed_quantity,reason,status,seller_deadline,hard_deadline,version,opened_at,created_at,updated_at) VALUES (?,?,?,1,'QUANTITY','UNDER_REVIEW',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            secondDispute.toString(), f.order().toString(), f.buyer().toString());
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { ready.countDown(); start.await(5, TimeUnit.SECONDS); return returns.resolve(f.dispute(), DisputeDecision.REFUND_ONLY, 1, ReturnProofType.SELLER_CONFIRMED, "seller-a", ProofAuthority.seller(f.seller())); });
            var second = pool.submit(() -> { ready.countDown(); start.await(5, TimeUnit.SECONDS); return returns.resolve(secondDispute, DisputeDecision.REFUND_ONLY, 1, ReturnProofType.SELLER_CONFIRMED, "seller-b", ProofAuthority.seller(f.seller())); });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var firstResult = first.get(20, TimeUnit.SECONDS);
            var secondResult = second.get(20, TimeUnit.SECONDS);
            provider.setRefundStatus(refunds.queryRefund(firstResult.refundId()).providerReference(), "SUCCEEDED");
            provider.setRefundStatus(refunds.queryRefund(secondResult.refundId()).providerReference(), "SUCCEEDED");
            refunds.reconcileRefund(firstResult.refundId());
            refunds.reconcileRefund(secondResult.refundId());
            returns.reconcileSuccessfulRefund(firstResult.refundId());
            returns.reconcileSuccessfulRefund(secondResult.refundId());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_order WHERE order_id=?", Integer.class, f.order().toString())).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(amount_fen),0) FROM refund_order WHERE order_id=?", Long.class, f.order().toString())).isEqualTo(200L);
            assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(approved_quantity),0) FROM dispute_case WHERE order_id=? AND status='RESOLVED'", Integer.class, f.order().toString())).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, f.order().toString())).isEqualTo("REFUNDED");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void settlementAndNewRefundRaceNeverSettlesThenCreatesRefund() throws Exception {
        Fixture f = fixture(1, 100);
        jdbc.update("UPDATE trade_order SET status='AFTERSALE_WINDOW',t0=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 8 DAY) WHERE id=?", f.order().toString());
        jdbc.update("DELETE FROM dispute_case WHERE id=?", f.dispute().toString());
        var pool = Executors.newFixedThreadPool(2);
        try {
            provider.blockNextRefundCreateForTest();
            var refund = pool.submit(() -> refunds.requestRefund(f.order(), "settlement-race-" + f.order(), com.example.campusmarket.shared.Money.ofFen(100)));
            assertThat(provider.awaitRefundCreateEnteredForTest(10, TimeUnit.SECONDS)).isTrue();
            var settlement = pool.submit(() -> settlements.settle(f.order()));
            var settled = settlement.get(20, TimeUnit.SECONDS);
            provider.releaseBlockedRefundCreateForTest();
            RefundService.RefundResult refundResult = refund.get(20, TimeUnit.SECONDS);
            assertThat(settled.status()).isEqualTo("BLOCKED");
            assertThat(refundResult.refundId()).isNotNull();
            assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, f.order().toString())).isEqualTo("AFTERSALE_WINDOW");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_order WHERE order_id=?", Integer.class, f.order().toString())).isEqualTo(1);
        } finally {
            provider.releaseBlockedRefundCreateForTest();
            pool.shutdownNow();
        }
    }

    @Test
    void settlementFirstThenRefundWaitsForOrderLockAndCannotCreateAfterSettlement() throws Exception {
        Fixture f = fixture(1, 100);
        jdbc.update("UPDATE trade_order SET status='AFTERSALE_WINDOW',t0=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 8 DAY) WHERE id=?", f.order().toString());
        jdbc.update("DELETE FROM dispute_case WHERE id=?", f.dispute().toString());
        UUID settlementId = UUID.nameUUIDFromBytes(("settlement:" + f.order()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO settlement (id,order_id,paid_amount_fen,successful_refund_fen,net_settlement_fen,status,created_at,settled_at) VALUES (?,?,100,0,100,'PENDING',CURRENT_TIMESTAMP(6),NULL)",
            settlementId.toString(), f.order().toString());
        CountDownLatch settlementRowLocked = new CountDownLatch(1);
        CountDownLatch releaseSettlementRow = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(3);
        var blocker = pool.submit(() -> {
            TransactionStatus transaction = transactionManager.getTransaction(new DefaultTransactionDefinition());
            boolean committed = false;
            try {
                jdbc.queryForObject("SELECT id FROM settlement WHERE order_id=? FOR UPDATE", String.class, f.order().toString());
                settlementRowLocked.countDown();
                releaseSettlementRow.await(10, TimeUnit.SECONDS);
                transactionManager.commit(transaction);
                committed = true;
            } finally {
                if (!committed) transactionManager.rollback(transaction);
            }
            return null;
        });
        try {
            var settlement = pool.submit(() -> settlements.settle(f.order()));
            assertThat(settlementRowLocked.await(10, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(lockObserver.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                    + "JOIN performance_schema.data_locks requesting ON requesting.ENGINE='INNODB' "
                    + "AND requesting.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID "
                    + "AND requesting.ENGINE_TRANSACTION_ID=w.REQUESTING_ENGINE_TRANSACTION_ID "
                    + "JOIN performance_schema.data_locks blocking ON blocking.ENGINE='INNODB' "
                    + "AND blocking.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID "
                    + "AND blocking.ENGINE_TRANSACTION_ID=w.BLOCKING_ENGINE_TRANSACTION_ID "
                    + "WHERE requesting.OBJECT_SCHEMA=DATABASE() AND requesting.OBJECT_NAME='settlement' "
                    + "AND requesting.LOCK_STATUS='WAITING' AND blocking.OBJECT_SCHEMA=DATABASE() "
                    + "AND blocking.OBJECT_NAME='settlement' AND blocking.LOCK_STATUS='GRANTED'", Long.class))
                    .isGreaterThan(0L));
            var refund = pool.submit(() -> {
                try {
                    return refunds.requestRefund(f.order(), "settlement-first-" + f.order(), com.example.campusmarket.shared.Money.ofFen(100));
                } catch (RuntimeException failure) {
                    return null;
                }
            });
            releaseSettlementRow.countDown();
            assertThat(settlement.get(20, TimeUnit.SECONDS).status()).isEqualTo("SETTLED");
            assertThat(refund.get(20, TimeUnit.SECONDS)).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_order WHERE order_id=?", Integer.class, f.order().toString())).isZero();
        } finally {
            releaseSettlementRow.countDown();
            blocker.get(20, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
    }

    @Test
    void sellerExplicitWriteOffConsumesQuarantineIdempotentlyAndChecksQuantity() {
        Fixture f = fixture(2, 100);
        assertThat(inventory.quarantine(f.listing(), 2, "return-q-writeoff-" + f.listing())).isTrue();
        assertThat(inventory.scrapQuarantined(f.listing(), 1, "return-writeoff-" + f.listing())).isTrue();
        assertThat(inventory.scrapQuarantined(f.listing(), 1, "return-writeoff-" + f.listing())).isTrue();
        assertThat(jdbc.queryForObject("SELECT quarantined_quantity FROM listing WHERE id=?", Integer.class, f.listing().toString())).isEqualTo(1);
        assertThat(inventory.scrapQuarantined(f.listing(), 2, "return-writeoff-too-much-" + f.listing())).isFalse();
        assertThat(jdbc.queryForObject("SELECT quarantined_quantity FROM listing WHERE id=?", Integer.class, f.listing().toString())).isEqualTo(1);
        assertThat(inventory.scrapQuarantined(f.listing(), 1, "return-writeoff-final-" + f.listing())).isTrue();
        assertThat(jdbc.queryForObject("SELECT available_quantity FROM listing WHERE id=?", Integer.class, f.listing().toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT quarantined_quantity FROM listing WHERE id=?", Integer.class, f.listing().toString())).isZero();
    }

    private Fixture fixture(int quantity, long unitPrice) {
        UUID buyer = user(), seller = user(), listing = UUID.randomUUID(), order = UUID.randomUUID(), payment = UUID.randomUUID(), dispute = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString(), "教材", "描述", "教材", unitPrice);
        jdbc.update("INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,t0,created_at,updated_at) VALUES (?,?,?,?,?,?,?, ?,?,?, 'DISPUTED',0,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 8 DAY),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", order.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "描述", unitPrice, quantity, unitPrice * quantity, unitPrice * quantity);
        jdbc.update("INSERT INTO payment_order (id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,'SUCCEEDED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", payment.toString(), order.toString(), "simulated", "pay-" + payment, unitPrice * quantity, unitPrice * quantity, "sim-pay-" + payment);
        jdbc.update("INSERT INTO dispute_case (id,order_id,initiator_id,disputed_quantity,reason,status,seller_deadline,hard_deadline,version,opened_at,created_at,updated_at) VALUES (?,?,?,?,'QUANTITY','UNDER_REVIEW',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", dispute.toString(), order.toString(), buyer.toString(), quantity);
        return new Fixture(listing, order, payment, dispute, seller, buyer);
    }

    private record Fixture(UUID listing, UUID order, UUID payment, UUID dispute, UUID seller, UUID buyer) {}

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_user (id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", id.toString(), id + "@stu.example.edu.cn", "hash");
        return id;
    }
}
