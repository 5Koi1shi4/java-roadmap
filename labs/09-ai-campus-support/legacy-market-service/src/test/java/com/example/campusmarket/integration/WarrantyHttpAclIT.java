package com.example.campusmarket.integration;

import com.example.campusmarket.legacy.LegacyMarketApplication;
import com.example.campusmarket.storage.PrivateObjectStorage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP/ACL contract with MySQL and an in-memory object store. The fake
 * still performs physical byte writes and reads through EvidenceStorage. */
@SpringBootTest(classes = LegacyMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestPropertySource(properties = {
    "campus.market.search.dispatcher.enabled=false", "campus.market.dispute.deadline.enabled=false",
    "campus.market.dispute.return-reconciliation.enabled=false", "campus.market.warranty.deadline.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(WarrantyHttpAclIT.FakeStorageConfig.class)
@AutoConfigureTestRestTemplate
class WarrantyHttpAclIT extends Task11MySqlContainers {
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
    }

    @Test
    void unauthenticatedAndProtocolErrorsAreRealHttpJsonResponses() {
        ResponseEntity<String> unauth = http.postForEntity("/api/orders/" + UUID.randomUUID() + "/warranty",
            new HttpEntity<>("{}", jsonHeaders()), String.class);
        assertThat(unauth.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        UUID buyer = user();
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(ResourceServerTestSupport.token(buyer, Set.of("ROLE_USER")));
        ResponseEntity<String> missingKey = http.postForEntity("/api/orders/" + UUID.randomUUID() + "/warranty",
            new HttpEntity<>("{\"quantity\":1,\"reason\":\"FUNCTIONAL_DEFECT\"}", headers), String.class);
        assertThat(missingKey.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missingKey.getHeaders().getContentType().toString()).startsWith("application/json");
    }

    @Test
    void nonParticipantAndMissingCaseHaveIdenticalHttp404() {
        UUID participant = user(), outsider = user(), seller = user(), caseId = UUID.randomUUID();
        UUID order = UUID.randomUUID(), listing = UUID.randomUUID();
        jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,'desc','digital',100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString(), "keyboard");
        jdbc.update("INSERT INTO trade_order(id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,warranty_days,warranty_scope_snapshot,status,version,t0,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,100,90,'scope','SETTLED',0,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 8 DAY),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", order.toString(), participant.toString(), seller.toString(), listing.toString(), "keyboard", "desc");
        jdbc.update("INSERT INTO warranty_case(id,order_id,idempotency_key,buyer_id,seller_id,warranty_days,warranty_scope_snapshot,disputed_quantity,reason,status,seller_deadline,version,opened_at,created_at,updated_at) VALUES (?,?,?,?,?,90,'scope',1,'FUNCTIONAL_DEFECT','OPEN',DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            caseId.toString(), order.toString(), "http-" + caseId, participant.toString(), seller.toString());
        HttpHeaders outsiderHeaders = jsonHeaders();
        outsiderHeaders.setBearerAuth(ResourceServerTestSupport.token(outsider, Set.of("ROLE_USER")));
        ResponseEntity<String> denied = http.exchange("/api/warranty-cases/" + caseId, HttpMethod.GET,
            new HttpEntity<>(outsiderHeaders), String.class);
        ResponseEntity<String> absent = http.exchange("/api/warranty-cases/" + UUID.randomUUID(), HttpMethod.GET,
            new HttpEntity<>(outsiderHeaders), String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(absent.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(denied.getHeaders().getContentType().toString()).startsWith("application/json");
        assertThat(absent.getHeaders().getContentType().toString()).startsWith("application/json");
    }

    @Test
    void evidenceMultipartIsPhysicallyWrittenAndReadThroughHttpAcl() {
        UUID buyer = user(), seller = user(), listing = UUID.randomUUID(), order = UUID.randomUUID(), caseId = UUID.randomUUID();
        jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,version,created_at,updated_at) VALUES (?,?,?,'desc','digital',100,0,'SOLD_OUT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", listing.toString(), seller.toString(), "keyboard");
        jdbc.update("INSERT INTO trade_order(id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,paid_amount_fen,warranty_days,warranty_scope_snapshot,status,version,t0,created_at,updated_at) VALUES (?,?,?,?,?,?,100,1,100,100,90,'scope','SETTLED',0,DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 8 DAY),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", order.toString(), buyer.toString(), seller.toString(), listing.toString(), "keyboard", "desc");
        jdbc.update("INSERT INTO warranty_case(id,order_id,idempotency_key,buyer_id,seller_id,warranty_days,warranty_scope_snapshot,disputed_quantity,reason,status,seller_deadline,version,opened_at,created_at,updated_at) VALUES (?,?,?,?,?,90,'scope',1,'FUNCTIONAL_DEFECT','OPEN',DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", caseId.toString(), order.toString(), "evidence-" + caseId, buyer.toString(), seller.toString());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ResourceServerTestSupport.token(buyer, Set.of("ROLE_USER")));
        headers.set("X-Evidence-Purpose", "REPAIR_QUOTE");
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource("%PDF-1.4\nproof\n%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)) {
            @Override public String getFilename() { return "quote.pdf"; }
        });
        ResponseEntity<String> uploaded = http.postForEntity("/api/warranty-cases/" + caseId + "/evidence",
            new HttpEntity<>(form, headers), String.class);
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String evidenceId = uploaded.getBody().replaceAll(".*\\\"evidenceId\\\":\\\"([^\\\"]+).*", "$1");
        ResponseEntity<byte[]> read = http.exchange("/api/warranty-cases/" + caseId + "/evidence/" + evidenceId + "/content",
            HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody()).containsExactly("%PDF-1.4\nproof\n%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        HttpHeaders malformedHeaders = new HttpHeaders();
        malformedHeaders.setBearerAuth(ResourceServerTestSupport.token(buyer, Set.of("ROLE_USER")));
        malformedHeaders.setContentType(MediaType.parseMediaType("multipart/form-data"));
        ResponseEntity<String> malformed = http.postForEntity("/api/warranty-cases/" + caseId + "/evidence",
            new HttpEntity<>("not-a-multipart-body", malformedHeaders), String.class);
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(malformed.getHeaders().getContentType().toString()).startsWith("application/json");
    }

    private UUID user() {
        return UUID.randomUUID();
    }
    private static HttpHeaders jsonHeaders() { HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); return h; }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeStorageConfig {
        @Bean @Primary PrivateObjectStorage evidenceObjectStore() { return new InMemoryStorage(); }
    }
    static final class InMemoryStorage implements PrivateObjectStorage {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        public void put(String key, InputStream content, long size, String type) { try { objects.put(key, content.readAllBytes()); } catch (java.io.IOException e) { throw new IllegalStateException(e); } }
        public InputStream open(String key) { byte[] bytes = objects.get(key); if (bytes == null) throw new IllegalStateException("missing object"); return new ByteArrayInputStream(bytes); }
        public void delete(String key) { objects.remove(key); }
    }
}
