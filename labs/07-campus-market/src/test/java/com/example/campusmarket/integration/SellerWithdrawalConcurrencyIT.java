package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.catalog.application.ListingService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
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

/** Proves the seller identity row is a real balance mutex and idempotent
 * replay is resolved before a later restriction gate. */
@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestPropertySource(properties = {
    "campus.market.search.dispatcher.enabled=false", "campus.market.dispute.deadline.enabled=false",
    "campus.market.dispute.return-reconciliation.enabled=false", "campus.market.warranty.deadline.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SellerWithdrawalConcurrencyIT extends Task11MySqlContainers {
    @Autowired JdbcTemplate jdbc;
    @Autowired ListingService listings;

    @BeforeAll
    static void migrate() { Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate(); }

    @Test
    void concurrentRequestsCannotSpendTheSameSettlementAndReplaySurvivesRestriction() throws Exception {
        Fixture fixture = fixture();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> listings.withdraw(fixture.seller(), 700, "w-1"));
            Future<?> second = pool.submit(() -> listings.withdraw(fixture.seller(), 700, "w-2"));
            int successes = 0;
            for (Future<?> f : new Future<?>[]{first, second}) {
                try { f.get(20, TimeUnit.SECONDS); successes++; }
                catch (ExecutionException e) { assertThat(e.getCause()).isInstanceOf(ListingService.InsufficientBalanceException.class); }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(amount_fen),0) FROM seller_withdrawal WHERE seller_id=? AND status='REQUESTED'", Long.class, fixture.seller().toString())).isEqualTo(700L);

            UUID existing = listings.withdraw(fixture.seller(), 700, "w-1");
            jdbc.update("INSERT INTO seller_account_restriction(seller_id,restriction_type,source_obligation_id,status,created_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6))", fixture.seller().toString(), "WITHDRAW", fixture.obligation().toString());
            assertThat(listings.withdraw(fixture.seller(), 700, "w-1")).isEqualTo(existing);
        } finally { pool.shutdownNow(); }
    }

    private Fixture fixture() {
        UUID seller = user(), buyer = user(), listing = UUID.randomUUID(), order = UUID.randomUUID(), settlement = UUID.randomUUID(), caseId = UUID.randomUUID(), obligation = UUID.randomUUID();
        jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,'desc','digital',100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString(), "keyboard");
        jdbc.update("INSERT INTO trade_order(id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,warranty_days,status,version,t0,created_at,updated_at) VALUES (?,?,?,?,?,?,1000,1,1000,1000,90,'SETTLED',0,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 8 DAY),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", order.toString(), buyer.toString(), seller.toString(), listing.toString(), "keyboard", "desc");
        jdbc.update("INSERT INTO settlement(id,order_id,paid_amount_fen,successful_refund_fen,net_settlement_fen,status,created_at) VALUES (?,?,1000,0,1000,'SETTLED',CURRENT_TIMESTAMP(6))", settlement.toString(), order.toString());
        jdbc.update("INSERT INTO warranty_case(id,order_id,idempotency_key,buyer_id,seller_id,warranty_days,warranty_scope_snapshot,disputed_quantity,reason,status,seller_deadline,version,opened_at,created_at,updated_at) VALUES (?,?,?,?,?,90,'scope',1,'FUNCTIONAL_DEFECT','OPEN',DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 3 DAY),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", caseId.toString(), order.toString(), "withdraw-" + caseId, buyer.toString(), seller.toString());
        jdbc.update("INSERT INTO seller_obligation(id,warranty_case_id,seller_id,obligation_business_key,obligation_amount_fen,funding_deadline,restriction_status,status,version,created_at,updated_at) VALUES (?,?,?, ?,100,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 3 DAY),'RESTRICTED','AWAITING_FUNDING',1,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", obligation.toString(), caseId.toString(), seller.toString(), "withdraw-" + obligation);
        return new Fixture(seller, obligation);
    }
    private UUID user() { UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO campus_user(id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",id.toString(),id+"@withdraw.example.edu.cn","hash"); return id; }
    private record Fixture(UUID seller, UUID obligation) {}
}
