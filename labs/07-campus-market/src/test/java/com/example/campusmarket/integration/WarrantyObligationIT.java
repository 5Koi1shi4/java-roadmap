package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.warranty.application.SellerObligationService;
import com.example.campusmarket.warranty.application.WarrantyService;
import com.example.campusmarket.warranty.domain.WarrantyDecision;
import com.example.campusmarket.dispute.infrastructure.JdbcEvidenceCaseAccess;
import com.example.campusmarket.payment.application.SettlementService;
import com.example.campusmarket.shared.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 MySQL：结算后质保义务、退款额度、限制与抵扣的幂等协作。 */
@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "server.port=18085", "campus.market.payment.provider-url=http://localhost:18085/simulated-provider",
    "campus.market.search.dispatcher.enabled=false", "campus.market.dispute.deadline.enabled=false",
    "campus.market.dispute.return-reconciliation.enabled=false", "campus.market.warranty.deadline.enabled=false",
    "spring.rabbitmq.listener.simple.auto-startup=false", "spring.rabbitmq.listener.direct.auto-startup=false"
})
class WarrantyObligationIT extends Task11MySqlContainers {
    @Autowired JdbcTemplate jdbc;
    @Autowired WarrantyService warranties;
    @Autowired SellerObligationService obligations;
    @Autowired SettlementService settlements;
    @Autowired JdbcEvidenceCaseAccess evidenceAccess;

    @Test
    void settledWarrantyDecisionCreatesRestrictedObligationAndFundingIsIdempotent() {
        Fixture f = fixture(5000, 30);
        UUID admin = user();
        WarrantyService.Result opened = warranties.openWarrantyCase(f.order(), 1, "FUNCTIONAL_DEFECT", "warranty-open-" + f.order(), f.buyer());
        assertThat(opened.status()).isEqualTo("OPEN");
        UUID quote = evidence(opened.caseId(), f.buyer());
        WarrantyService.Result decided = warranties.decide(opened.caseId(), admin, WarrantyDecision.REPAIR_COMPENSATION, 1200, quote.toString());
        assertThat(decided.status()).isEqualTo("RESOLVED");
        UUID obligation = UUID.fromString(jdbc.queryForObject("SELECT id FROM seller_obligation WHERE warranty_case_id=?", String.class, opened.caseId().toString()));
        assertThat(jdbc.queryForObject("SELECT obligation_amount_fen FROM seller_obligation WHERE id=?", Long.class, obligation.toString())).isEqualTo(1200L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller_account_restriction WHERE source_obligation_id=? AND status='ACTIVE'", Integer.class, obligation.toString())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, f.order().toString())).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement WHERE order_id=?", Integer.class, f.order().toString())).isZero();

        assertThat(obligations.fundObligation(obligation, f.seller(), Money.ofFen(1200)).status()).isEqualTo("FUNDED");
        assertThat(obligations.fundObligation(obligation, f.seller(), Money.ofFen(1200)).status()).isEqualTo("FUNDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_order WHERE order_id=? AND source_type='WARRANTY'", Integer.class, f.order().toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller_account_restriction WHERE source_obligation_id=? AND status='ACTIVE'", Integer.class, obligation.toString())).isZero();
    }

    @Test
    void futureSettlementDeductionUsesCompositeIdempotencyAndAclIsParticipantOnly() throws Exception {
        Fixture f = fixture(1000, 30);
        UUID admin = user();
        WarrantyService.Result opened = warranties.openWarrantyCase(f.order(), 1, "FUNCTIONAL_DEFECT", "warranty-deduct-" + f.order(), f.buyer());
        UUID quote = evidence(opened.caseId(), f.buyer());
        warranties.decide(opened.caseId(), admin, WarrantyDecision.REPAIR_COMPENSATION, 800, quote.toString());
        UUID obligation = UUID.fromString(jdbc.queryForObject("SELECT id FROM seller_obligation WHERE warranty_case_id=?", String.class, opened.caseId().toString()));
        UUID settlement = UUID.randomUUID();
        jdbc.update("INSERT INTO settlement(id,order_id,paid_amount_fen,successful_refund_fen,net_settlement_fen,status,created_at) VALUES (?,?,1000,0,1000,'SETTLED',CURRENT_TIMESTAMP(6))", settlement.toString(), f.order().toString());
        assertThat(obligations.deductFutureSettlement(settlement, obligation, Money.ofFen(800))).isEqualTo(800L);
        assertThat(obligations.deductFutureSettlement(settlement, obligation, Money.ofFen(800))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_obligation_deduction WHERE settlement_id=? AND obligation_id=?", Integer.class, settlement.toString(), obligation.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT funded_amount_fen FROM seller_obligation WHERE id=?", Long.class, obligation.toString())).isEqualTo(800L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox WHERE event_type='WARRANTY_REFUND_REQUESTED' AND aggregate_id=?", Integer.class, obligation.toString())).isEqualTo(1);
        String refundPayload=jdbc.queryForObject("SELECT CAST(payload AS CHAR) FROM integration_outbox WHERE event_type='WARRANTY_REFUND_REQUESTED' AND aggregate_id=?", String.class, obligation.toString());
        var payload = new ObjectMapper().readTree(refundPayload);
        assertThat(payload.path("caseId").asText()).isEqualTo(opened.caseId().toString());
        assertThat(payload.path("orderId").asText()).isEqualTo(f.order().toString());
        assertThat(payload.path("obligationId").asText()).isEqualTo(obligation.toString());
        assertThat(payload.path("amountFen").asLong()).isEqualTo(800L);
        assertThat(evidenceAccess.canRead("WARRANTY", opened.caseId(), f.buyer())).isTrue();
        assertThat(evidenceAccess.canRead("WARRANTY", opened.caseId(), UUID.randomUUID())).isFalse();
    }

    private Fixture fixture(long paid, int daysAgo) {
        UUID buyer = user(), seller = user(), listing = UUID.randomUUID(), order = UUID.randomUUID(), payment = UUID.randomUUID();
        jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,'数码',?,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString(), "键盘", "描述", paid);
        jdbc.update("INSERT INTO trade_order(id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,warranty_days,warranty_scope_snapshot,status,version,t0,created_at,updated_at) VALUES (?,?,?,?,?,?,?,1,?,?,90,'SELLER_NON_HUMAN_FUNCTIONAL_FAILURE','SETTLED',0,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL ? DAY),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", order.toString(), buyer.toString(), seller.toString(), listing.toString(), "键盘", "描述", paid, paid, paid, daysAgo);
        jdbc.update("INSERT INTO payment_order(id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,'SUCCEEDED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", payment.toString(), order.toString(), "simulated", "pay-" + payment, paid, paid, "sim-pay-" + payment);
        return new Fixture(buyer, seller, order);
    }
    private UUID user() { UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO campus_user(id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",id.toString(),id+"@stu.example.edu.cn","hash"); return id; }
    private UUID evidence(UUID caseId, UUID actor) { UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO dispute_evidence(id,dispute_case_id,warranty_case_id,case_type,submitted_by,object_key,media_type,size_bytes,created_at) VALUES (?,NULL,?,'WARRANTY',?,?, 'application/pdf',4,CURRENT_TIMESTAMP(6))",id.toString(),caseId.toString(),actor.toString(),"fixture-proof-"+caseId); return id; }
    private record Fixture(UUID buyer, UUID seller, UUID order) {}
}
