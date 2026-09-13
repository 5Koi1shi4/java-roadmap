package com.example.campusmarket.integration;

import com.example.campusmarket.legacy.LegacyMarketApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = LegacyMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestPropertySource(properties = {
    "campus.market.search.dispatcher.enabled=false", "campus.market.dispute.deadline.enabled=false",
    "campus.market.dispute.return-reconciliation.enabled=false", "campus.market.warranty.deadline.enabled=false",
    "campus.market.order.deadline.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiSecurityAndReviewIT extends Task11MySqlContainers {
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
    }

    @Test
    void settledParticipantsCanReviewOnlyOnceAndPrivateOrderIs404() {
        UUID buyer = user(), seller = user(), outsider = user(), order = settledOrder(buyer, seller);
        String json = "{\"rating\":5,\"reviewText\":\"交易顺利\"}";

        ResponseEntity<String> unauthenticated = http.postForEntity("/api/orders/" + order + "/reviews",
            new HttpEntity<>(json, jsonHeaders()), String.class);
        assertThat(unauthenticated.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> first = http.postForEntity("/api/orders/" + order + "/reviews",
            new HttpEntity<>(json, authHeaders(buyer)), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getContentType().toString()).isEqualTo("application/json;charset=UTF-8");

        ResponseEntity<String> duplicate = http.postForEntity("/api/orders/" + order + "/reviews",
            new HttpEntity<>(json, authHeaders(buyer)), String.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<String> denied = http.postForEntity("/api/orders/" + order + "/reviews",
            new HttpEntity<>(json, authHeaders(outsider)), String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> second = http.postForEntity("/api/orders/" + order + "/reviews",
            new HttpEntity<>(json, authHeaders(seller)), String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_review WHERE order_id=?", Integer.class, order.toString())).isEqualTo(2);
    }

    @Test
    void unknownJsonFieldAndChineseErrorUseFixedUtf8Protocol() {
        UUID buyer = user(), seller = user(), order = settledOrder(buyer, seller);
        HttpHeaders headers = authHeaders(buyer);
        ResponseEntity<String> response = http.postForEntity("/api/orders/" + order + "/reviews",
            new HttpEntity<>("{\"rating\":5,\"reviewText\":\"好\",\"unexpected\":true}", headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType().getCharset()).isEqualTo(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(response.getBody()).contains("请求参数无效");
    }

    private UUID user() {
        return UUID.randomUUID();
    }

    private UUID settledOrder(UUID buyer, UUID seller) {
        UUID listing = UUID.randomUUID(), order = UUID.randomUUID();
        jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,'desc','book',100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            listing.toString(), seller.toString(), "教材");
        jdbc.update("INSERT INTO trade_order(id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,warranty_days,status,version,t0,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,100,NULL,'SETTLED',0,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 8 DAY),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            order.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "desc");
        return order;
    }

    private HttpHeaders authHeaders(UUID id) {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(ResourceServerTestSupport.token(id, Set.of("ROLE_USER")));
        return headers;
    }
    private static HttpHeaders jsonHeaders() { HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); return h; }
}
