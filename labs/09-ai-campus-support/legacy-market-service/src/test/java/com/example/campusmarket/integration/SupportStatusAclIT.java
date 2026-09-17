package com.example.campusmarket.integration;

import com.example.campusmarket.legacy.LegacyMarketApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.util.UriComponentsBuilder;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import java.nio.file.Path;
import java.net.URISyntaxException;

import static org.assertj.core.api.Assertions.assertThat;

/** Real MySQL/HTTP ACL contract for the read-only support status surface. */
@SpringBootTest(classes = LegacyMarketApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestPropertySource(properties = {
    "campus.market.search.dispatcher.enabled=false",
    "campus.market.dispute.deadline.enabled=false",
    "campus.market.dispute.return-reconciliation.enabled=false",
    "campus.market.warranty.deadline.enabled=false",
    "campus.market.order.deadline.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureTestRestTemplate
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SupportStatusAclIT extends Task11MySqlContainers {
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .locations("filesystem:" + legacyMigrationDirectory())
            .load().migrate();
    }

    @DynamicPropertySource
    static void flywayLocations(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.locations", () -> "filesystem:" + legacyMigrationDirectory());
    }

    private static Path legacyMigrationDirectory() {
        try {
            Path legacyClasses = Path.of(LegacyMarketApplication.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
            return legacyClasses.resolve("db").resolve("migration").toAbsolutePath().normalize();
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("无法定位 legacy 迁移目录", ex);
        }
    }

    @Test
    @Order(1)
    void ownOrderVisibleAndOthersIndistinguishableFromMissing() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID order = order(buyer, seller);

        ResponseEntity<String> own = http.exchange("/api/support/orders/" + order,
            org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(authHeaders(buyer)), String.class);
        ResponseEntity<String> other = http.exchange("/api/support/orders/" + order,
            org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(authHeaders(stranger)), String.class);
        ResponseEntity<String> missing = http.exchange("/api/support/orders/" + UUID.randomUUID(),
            org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(authHeaders(stranger)), String.class);

        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.OK);
        MediaType ownContentType = own.getHeaders().getContentType();
        assertThat(ownContentType).isNotNull();
        assertThat(ownContentType.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(ownContentType.getCharset()).isEqualTo(StandardCharsets.UTF_8);
        assertJsonNotFound(other);
        assertJsonNotFound(missing);
    }

    private void assertJsonNotFound(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        MediaType contentType = response.getHeaders().getContentType();
        assertThat(contentType).isNotNull();
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(contentType.getCharset()).isEqualTo(StandardCharsets.UTF_8);

        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.get("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(body.get("message").asText()).isEqualTo("资源不存在");
        assertThat(body.get("correlationId")).isNotNull();
        assertThat(UUID.fromString(body.get("correlationId").asText())).isNotNull();
    }

    @Test
    @Order(2)
    void unauthenticatedAndAdminStillRespectParticipantAcl() {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID order = order(buyer, seller);

        ResponseEntity<String> unauthenticated = http.getForEntity(
            "/api/support/orders/" + order, String.class);
        ResponseEntity<String> admin = exchange("/api/support/orders/" + order, stranger,
            Set.of("ROLE_ADMIN"));

        assertThat(unauthenticated.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unauthenticated.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
            .containsIgnoringCase("charset=UTF-8");
        assertThat(admin.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @Order(3)
    void listUsesBoundCursorAndStableOrdering() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        Instant newest = Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
        UUID firstId = orderAt(buyer, seller, newest);
        UUID secondId = orderAt(buyer, seller, newest.minus(1, ChronoUnit.SECONDS));
        UUID thirdId = orderAt(buyer, seller, newest.minus(2, ChronoUnit.SECONDS));

        ResponseEntity<String> first = exchange("/api/support/orders?limit=2", buyer,
            Set.of("ROLE_USER"));
        JsonNode firstPage = mapper.readTree(first.getBody());
        String cursor = firstPage.get("nextCursor").asText();
        String secondUri = UriComponentsBuilder.fromPath("/api/support/orders")
            .queryParam("limit", 2).queryParam("cursor", cursor).build().toUriString();
        ResponseEntity<String> second = exchange(secondUri, buyer, Set.of("ROLE_USER"));
        JsonNode secondPage = mapper.readTree(second.getBody());

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstPage.get("items")).hasSize(2);
        assertThat(firstPage.get("items").get(0).get("id").asText()).isEqualTo(firstId.toString());
        assertThat(firstPage.get("items").get(1).get("id").asText()).isEqualTo(secondId.toString());
        assertThat(cursor).isNotBlank();
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondPage.get("items")).hasSize(1);
        assertThat(secondPage.get("items").get(0).get("id").asText()).isEqualTo(thirdId.toString());
        assertThat(secondPage.get("nextCursor").isNull()).isTrue();

        ResponseEntity<String> cursorForOtherUser = exchange(secondUri, UUID.randomUUID(),
            Set.of("ROLE_USER"));
        assertThat(cursorForOtherUser.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @Order(4)
    void disputesAndWarrantiesAreParticipantScoped() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID order = order(buyer, seller);
        UUID dispute = UUID.randomUUID();
        UUID warranty = UUID.randomUUID();
        Instant opened = Instant.now().minus(10, ChronoUnit.SECONDS).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("INSERT INTO dispute_case(id,order_id,initiator_id,disputed_quantity,reason,status,seller_deadline,admin_deadline,decision,approved_quantity,version,opened_at,created_at,updated_at) "
                + "VALUES (?,?,?,1,'QUALITY','OPEN',?,NULL,NULL,NULL,0,?,?,?)",
            dispute.toString(), order.toString(), buyer.toString(),
            Timestamp.from(opened.plus(1, ChronoUnit.DAYS)), Timestamp.from(opened),
            Timestamp.from(opened), Timestamp.from(opened));
        jdbc.update("INSERT INTO warranty_case(id,order_id,idempotency_key,buyer_id,seller_id,warranty_days,warranty_scope_snapshot,manufacturer_warranty_proof_snapshot,manufacturer_warranty_expires_at,disputed_quantity,reason,status,seller_deadline,admin_deadline,decision,compensation_amount_fen,version,opened_at,closed_at,created_at,updated_at) "
                + "VALUES (?,?,?,?,?,7,'scope',NULL,NULL,1,'QUALITY','OPEN',?,NULL,NULL,0,0,?,NULL,?,?)",
            warranty.toString(), order.toString(), "support-" + warranty, buyer.toString(), seller.toString(),
            Timestamp.from(opened.plus(2, ChronoUnit.DAYS)), Timestamp.from(opened),
            Timestamp.from(opened), Timestamp.from(opened));

        ResponseEntity<String> ownDispute = exchange("/api/support/disputes/" + dispute, buyer,
            Set.of("ROLE_USER"));
        ResponseEntity<String> otherDispute = exchange("/api/support/disputes/" + dispute, stranger,
            Set.of("ROLE_USER"));
        ResponseEntity<String> ownWarranty = exchange("/api/support/warranties/" + warranty, seller,
            Set.of("ROLE_USER"));
        ResponseEntity<String> otherWarranty = exchange("/api/support/warranties?limit=10", stranger,
            Set.of("ROLE_USER"));
        JsonNode warrantyList = mapper.readTree(otherWarranty.getBody());

        assertThat(ownDispute.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(ownDispute.getBody()).get("type").asText()).isEqualTo("disputes");
        assertThat(otherDispute.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ownWarranty.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(ownWarranty.getBody()).get("type").asText()).isEqualTo("warranties");
        assertThat(otherWarranty.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(warrantyList.get("items")).isEmpty();
    }

    @Test
    @Order(5)
    void invalidTypeLimitAndCursorReturnJsonBadRequest() {
        UUID user = UUID.randomUUID();
        ResponseEntity<String> unknownType = exchange("/api/support/orderz", user,
            Set.of("ROLE_USER"));
        ResponseEntity<String> tooLarge = exchange("/api/support/orders?limit=21", user,
            Set.of("ROLE_USER"));
        ResponseEntity<String> malformedCursor = exchange("/api/support/orders?cursor=not-a-cursor", user,
            Set.of("ROLE_USER"));

        assertThat(unknownType.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooLarge.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(malformedCursor.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooLarge.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
            .containsIgnoringCase("charset=UTF-8");
        assertThat(tooLarge.getBody()).contains("请求参数无效");
    }

    @Test
    @Order(100)
    void databaseFailureMapsToBoundedJson503() {
        MYSQL.stop();
        try {
            ResponseEntity<String> response = exchange("/api/support/orders?limit=1", UUID.randomUUID(),
                Set.of("ROLE_USER"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
                .containsIgnoringCase("charset=UTF-8");
            assertThat(response.getBody()).contains("依赖服务暂时不可用");
        } finally {
            if (MYSQL.isRunning()) {
                MYSQL.stop();
            }
        }
    }

    private UUID order(UUID buyer, UUID seller) {
        return orderAt(buyer, seller, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private UUID orderAt(UUID buyer, UUID seller, Instant createdAt) {
        UUID listing = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) "
                + "VALUES (?,?,?,'support','book',100,0,'SOLD_OUT',0,?,?)",
            listing.toString(), seller.toString(), "support fixture", Timestamp.from(createdAt), Timestamp.from(createdAt));
        jdbc.update("INSERT INTO trade_order(id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,created_at,updated_at) "
                + "VALUES (?,?,?,?,?,?,100,1,100,100,'SETTLED',0,?,?)",
            order.toString(), buyer.toString(), seller.toString(), listing.toString(), "support fixture", "support",
            Timestamp.from(createdAt), Timestamp.from(createdAt));
        return order;
    }

    private ResponseEntity<String> exchange(String uri, UUID userId, Set<String> roles) {
        return http.exchange(uri, org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(authHeaders(userId, roles)), String.class);
    }

    private static HttpHeaders authHeaders(UUID userId) {
        return authHeaders(userId, Set.of("ROLE_USER"));
    }

    private static HttpHeaders authHeaders(UUID userId, Set<String> roles) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(ResourceServerTestSupport.token(userId, roles));
        return headers;
    }
}
