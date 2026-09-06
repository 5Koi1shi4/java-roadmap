package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.catalog.application.InventoryPort;
import com.example.campusmarket.dispute.application.ReturnResolutionService;
import com.example.campusmarket.dispute.application.ProofAuthority;
import com.example.campusmarket.dispute.domain.DisputeDecision;
import com.example.campusmarket.dispute.domain.ReturnProofType;
import com.example.campusmarket.payment.application.SettlementService;
import com.example.campusmarket.payment.application.RefundService;
import com.example.campusmarket.payment.infrastructure.SimulatedPaymentProviderController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实 MySQL：三件退一件只隔离一件，剩余金额可在七天后净结算。 */
@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {"server.port=18082", "campus.market.payment.provider-url=http://localhost:18082/simulated-provider",
    "campus.market.search.dispatcher.enabled=false", "campus.market.dispute.deadline.enabled=false",
    "campus.market.dispute.return-reconciliation.enabled=false"})
class PartialReturnRefundIT extends Task11MySqlContainers {
    @Autowired JdbcTemplate jdbc;
    @Autowired ReturnResolutionService returns;
    @Autowired SettlementService settlements;
    @Autowired RefundService refunds;
    @Autowired SimulatedPaymentProviderController provider;
    @Autowired InventoryPort inventory;

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
    void refundOnlySuccessDoesNotTouchQuarantine() {
        Fixture f = fixture(1, 100);
        jdbc.update("INSERT INTO return_proof_attestation (id,proof_reference,order_id,provider,delivered_quantity,status,verified_at,created_at) VALUES (?,?,?,?,1,'DELIVERED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            UUID.randomUUID().toString(), "provider-delivered", f.order().toString(), "simulated");
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
        return new Fixture(listing, order, payment, dispute, seller);
    }

    private record Fixture(UUID listing, UUID order, UUID payment, UUID dispute, UUID seller) {}

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_user (id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", id.toString(), id + "@stu.example.edu.cn", "hash");
        return id;
    }
}
