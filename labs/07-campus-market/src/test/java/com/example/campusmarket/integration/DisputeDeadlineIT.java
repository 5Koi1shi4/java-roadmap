package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.dispute.application.DisputeDeadlineScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 MySQL：卖家期限、管理员 SLA 与硬期限按数据库时间串行处理。 */
@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("local")
@TestPropertySource(properties = {"server.port=18083", "campus.market.payment.provider-url=http://localhost:18083/simulated-provider",
    "campus.market.search.dispatcher.enabled=false", "campus.market.dispute.deadline.enabled=false"})
class DisputeDeadlineIT extends SharedContainers {
    @Autowired JdbcTemplate jdbc;
    @Autowired DisputeDeadlineScheduler deadlines;

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
