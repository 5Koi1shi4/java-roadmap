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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class DisputeEvidenceIT extends SharedContainers {
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer<<>>\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    private final HttpClient client = HttpClient.newHttpClient();
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwt;

    @Test
    void caseParticipantsCanReadButOtherUsersReceiveIdentical404() throws Exception {
        UUID buyer = user("buyer"); UUID seller = user("seller"); UUID other = user("other");
        UUID listing = listing(seller); UUID order = order(listing, buyer, seller);
        UUID dispute = UUID.randomUUID();
        jdbc.update("INSERT INTO dispute_case(id,order_id,initiator_id,disputed_quantity,reason,status,seller_deadline,opened_at,created_at,updated_at) VALUES (?,?,?,1,'FUNCTIONAL_DEFECT','OPEN',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", dispute.toString(), order.toString(), buyer.toString());
        HttpResponse<String> upload = multipart("/api/disputes/" + dispute + "/evidence", token(buyer), "failure.png", "image/png", PNG);
        assertThat(upload.statusCode()).isEqualTo(201);
        String evidenceId = field(upload.body(), "evidenceId");
        assertThat(get("/api/disputes/" + dispute + "/evidence/" + evidenceId + "/content", token(buyer)).statusCode()).isEqualTo(200);
        assertThat(get("/api/disputes/" + dispute + "/evidence/" + evidenceId + "/content", token(seller)).statusCode()).isEqualTo(200);
        HttpResponse<String> denied = get("/api/disputes/" + dispute + "/evidence/" + evidenceId + "/content", token(other));
        HttpResponse<String> missing = get("/api/disputes/" + dispute + "/evidence/" + UUID.randomUUID() + "/content", token(other));
        assertThat(denied.statusCode()).isEqualTo(404);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(denied.body()).isEqualTo(missing.body());
        assertThat(upload.body()).doesNotContain("object_key", "dispute-evidence/");
    }

    @Test
    void rejectsDeclaredTypeForgeryAndLimitPlusOne() throws Exception {
        UUID buyer = user("buyer"); UUID seller = user("seller"); UUID listing = listing(seller); UUID order = order(listing, buyer, seller); UUID dispute = dispute(order, buyer);
        assertThat(multipart("/api/disputes/" + dispute + "/evidence", token(buyer), "bad.pdf", "application/pdf", PNG).statusCode()).isEqualTo(400);
        byte[] oversized = new byte[10 * 1024 * 1024 + 1]; System.arraycopy(PNG, 0, oversized, 0, PNG.length);
        assertThat(multipart("/api/disputes/" + dispute + "/evidence", token(buyer), "large.png", "image/png", oversized).statusCode()).isEqualTo(400);
    }

    @Test
    void minioDisconnectMapsTo503() throws Exception {
        UUID buyer = user("buyer"); UUID seller = user("seller"); UUID listing = listing(seller); UUID order = order(listing, buyer, seller); UUID dispute = dispute(order, buyer);
        MINIO_PROXY.setConnectionCut(true);
        try { assertThat(multipart("/api/disputes/" + dispute + "/evidence", token(buyer), "failure.png", "image/png", PNG).statusCode()).isEqualTo(503); }
        finally { MINIO_PROXY.setConnectionCut(false); }
    }

    private UUID user(String name) { UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO campus_user(id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", id.toString(), id+"@"+name+".stu.example.edu.cn", "hash"); return id; }
    private UUID listing(UUID seller) { UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,created_at,updated_at) VALUES (?,?, '键盘','二手','电子',100,0,'SOLD_OUT',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", id.toString(), seller.toString()); return id; }
    private UUID order(UUID listing, UUID buyer, UUID seller) { UUID id=UUID.randomUUID(); Instant t0=Instant.now().minusSeconds(3600); jdbc.update("INSERT INTO trade_order(id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,t0,acceptance_deadline,trial_deadline,paid_amount_fen,status,created_at,updated_at) VALUES (?,?,?,?, '键盘','二手',100,1,100,?,?,?,100,'AFTERSALE_WINDOW',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", id.toString(), buyer.toString(), seller.toString(), listing.toString(), java.sql.Timestamp.from(t0), java.sql.Timestamp.from(t0.plusSeconds(72*3600)), java.sql.Timestamp.from(t0.plusSeconds(7*86400))); return id; }
    private UUID dispute(UUID order, UUID buyer) { UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO dispute_case(id,order_id,initiator_id,disputed_quantity,reason,status,seller_deadline,opened_at,created_at,updated_at) VALUES (?,?,?,1,'FUNCTIONAL_DEFECT','OPEN',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", id.toString(), order.toString(), buyer.toString()); return id; }
    private String token(UUID user) { return jwt.issue(new AuthenticatedUser(user, Set.of("ROLE_USER"))); }
    private HttpResponse<String> get(String path, String token) throws Exception { return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization","Bearer "+token).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
    private HttpResponse<String> multipart(String path,String token,String filename,String type,byte[] bytes) throws Exception { String b="----campus"+UUID.randomUUID(); byte[] p=("--"+b+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\nContent-Type: "+type+"\r\n\r\n").getBytes(StandardCharsets.UTF_8), s=("\r\n--"+b+"--\r\n").getBytes(StandardCharsets.UTF_8), all=new byte[p.length+bytes.length+s.length]; System.arraycopy(p,0,all,0,p.length); System.arraycopy(bytes,0,all,p.length,bytes.length); System.arraycopy(s,0,all,p.length+bytes.length,s.length); return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization","Bearer "+token).header("Content-Type","multipart/form-data; boundary="+b).POST(HttpRequest.BodyPublishers.ofByteArray(all)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
    private static String field(String json,String field){java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\\""+field+"\\\"\\s*:\\s*\\\"([^\\\"]+)").matcher(json); if(!m.find()) throw new AssertionError(json); return m.group(1);}
}
