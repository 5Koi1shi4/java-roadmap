package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.dispute.application.ReturnResolutionService;
import com.example.campusmarket.dispute.domain.DisputeDecision;
import com.example.campusmarket.dispute.domain.ReturnProofType;
import com.example.campusmarket.payment.application.SettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 MySQL：三件退一件只隔离一件，剩余金额可在七天后净结算。 */
@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("local")
@TestPropertySource(properties = {"server.port=18082", "campus.market.payment.provider-url=http://localhost:18082/simulated-provider",
    "campus.market.search.dispatcher.enabled=false", "campus.market.dispute.deadline.enabled=false"})
class PartialReturnRefundIT extends SharedContainers {
    @Autowired JdbcTemplate jdbc;
    @Autowired ReturnResolutionService returns;
    @Autowired SettlementService settlements;

    @Test
    void returnsOneOfThreeQuarantinesOnlyOneAndSettlesTheRemainingNetAmount() {
        UUID buyer = user(), seller = user(), listing = UUID.randomUUID(), order = UUID.randomUUID(), payment = UUID.randomUUID(), dispute = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString(), "教材", "描述", "教材");
        jdbc.update("INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,t0,created_at,updated_at) VALUES (?,?,?,?,?,?,100,3,300,300,'DISPUTED',0,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 8 DAY),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", order.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "描述");
        jdbc.update("INSERT INTO payment_order (id,order_id,provider,idempotency_key,amount_fen,paid_amount_fen,provider_reference,status,created_at,updated_at) VALUES (?,?,?,?,300,300,?,'SUCCEEDED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", payment.toString(), order.toString(), "simulated", "pay-" + payment, "sim-pay-" + payment);
        jdbc.update("INSERT INTO dispute_case (id,order_id,initiator_id,disputed_quantity,reason,status,seller_deadline,hard_deadline,version,opened_at,created_at,updated_at) VALUES (?,?,?,1,'QUANTITY','UNDER_REVIEW',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),0, CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", dispute.toString(), order.toString(), buyer.toString());

        var result = returns.resolve(dispute, DisputeDecision.RETURN_AND_REFUND, 1, ReturnProofType.SELLER_CONFIRMED, "seller-confirmed");
        assertThat(result.refundStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.amountFen()).isEqualTo(100L);
        assertThat(jdbc.queryForObject("SELECT quarantined_quantity FROM listing WHERE id=?", Integer.class, listing.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT available_quantity FROM listing WHERE id=?", Integer.class, listing.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, order.toString())).isEqualTo("AFTERSALE_WINDOW");

        var settlement = settlements.settle(order);
        assertThat(settlement.status()).isEqualTo("SETTLED");
        assertThat(settlement.netSettlementFen()).isEqualTo(200L);
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_user (id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", id.toString(), id + "@stu.example.edu.cn", "hash");
        return id;
    }
}
