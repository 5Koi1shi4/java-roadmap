package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.identity.application.AuthenticatedUser;
import com.example.campusmarket.identity.infrastructure.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 交付 API 的真实 HTTP 状态码、私有资源边界和 UTF-8 响应测试。 */
@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestPropertySource(properties = {
    "campus.market.search.dispatcher.enabled=false",
    "campus.market.dispute.deadline.enabled=false",
    "campus.market.dispute.return-reconciliation.enabled=false",
    "campus.market.warranty.deadline.enabled=false",
    "campus.market.order.deadline.enabled=false",
    "campus.market.payment.reconciliation.enabled=false",
    "spring.rabbitmq.listener.simple.auto-startup=false",
    "spring.rabbitmq.listener.direct.auto-startup=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HandoffHttpIT extends Task11MySqlContainers {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;

    @Test
    void missingIdempotencyKeyIs400WithUtf8Json() throws Exception {
        UUID user = user();
        HttpResponse<byte[]> response = request(UUID.randomUUID(), user, null, "{}");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).containsIgnoringCase("charset=UTF-8");
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("幂等");
    }

    @Test
    void privateNonParticipantOrderIs404() throws Exception {
        UUID seller = user(); UUID buyer = user(); UUID outsider = user();
        UUID listing = UUID.randomUUID(); UUID order = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            listing.toString(), seller.toString(), "教材", "描述", "教材");
        jdbc.update("INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,handoff_deadline,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,100,'AWAITING_HANDOFF',0,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 HOUR),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            order.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "描述");
        HttpResponse<byte[]> response = request(order, outsider, "http-404-" + order, "{}");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("订单");
    }

    @Test
    void authenticatedWrongRoleIs403ForHandoffAndReceipt() throws Exception {
        UUID seller = user(); UUID buyer = user();
        UUID handoffOrder = insertOrder(seller, buyer, "AWAITING_HANDOFF");
        HttpResponse<byte[]> handoff = request(handoffOrder, buyer, "http-403-handoff-" + handoffOrder, "{}");
        assertThat(handoff.statusCode()).isEqualTo(403);

        UUID receiptOrder = insertOrder(seller, buyer, "AWAITING_RECEIPT");
        HttpResponse<byte[]> stateConflict = request(receiptOrder, seller, "http-409-state-" + receiptOrder, "{}");
        assertThat(stateConflict.statusCode()).isEqualTo(409);
        HttpResponse<byte[]> receipt = requestReceipt(receiptOrder, seller, "http-403-receipt-" + receiptOrder, "{}");
        assertThat(receipt.statusCode()).isEqualTo(403);
    }

    private HttpResponse<byte[]> request(UUID order, UUID actor, String key, String body) throws Exception {
        return requestPath("/handoff", order, actor, key, body);
    }

    private HttpResponse<byte[]> requestReceipt(UUID order, UUID actor, String key, String body) throws Exception {
        return requestPath("/receipt", order, actor, key, body);
    }

    private HttpResponse<byte[]> requestPath(String path, UUID order, UUID actor, String key, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders/" + order + path))
            .header("Authorization", "Bearer " + jwtService.issue(new AuthenticatedUser(actor, Set.of("ROLE_USER"))))
            .header("Content-Type", "application/json; charset=UTF-8");
        if (key != null) builder.header("Idempotency-Key", key);
        return HttpClient.newHttpClient().send(builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
            HttpResponse.BodyHandlers.ofByteArray());
    }

    private UUID insertOrder(UUID seller, UUID buyer, String status) {
        UUID listing = UUID.randomUUID(); UUID order = UUID.randomUUID();
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            listing.toString(), seller.toString(), "教材", "描述", "教材");
        String receipt = "AWAITING_RECEIPT".equals(status) ? "DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 HOUR)" : "NULL";
        jdbc.update("INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,handoff_deadline,receipt_deadline,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,100,?,0,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 HOUR)," + receipt + ",CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            order.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "描述", status);
        return order;
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_user (id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            id.toString(), id + "@stu.example.edu.cn", "hash");
        return id;
    }
}
