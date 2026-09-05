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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class DisputeEvidenceIT extends DisputeEvidenceContainers {
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    private static final byte[] JPEG = Base64.getDecoder().decode("/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////2wBDAf//////////////////////////////////////////////////////////////////////////////////////wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAX/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIQAxAAAAH/AP/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAT8Af//Z");
    private static final byte[] WEBP = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBAAAADQAQCdASoBAAEAAUAmJaQAA3AA/v89WAAAAAA=");
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer<<>>\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MP4 = new byte[] {0,0,0,24,'f','t','y','p','i','s','o','m',0,0,2,0,'i','s','o','m','i','s','o','2'};
    private final HttpClient client = HttpClient.newHttpClient();
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwt;

    @Test
    void caseParticipantsCanReadButOtherUsersReceiveIdentical404() throws Exception {
        UUID buyer = user("buyer"); UUID seller = user("seller"); UUID other = user("other");
        UUID assignedAdmin = user("assigned-admin"); UUID unassignedAdmin = user("unassigned-admin");
        UUID listing = listing(seller); UUID order = order(listing, buyer, seller);
        UUID dispute = UUID.randomUUID();
        jdbc.update("INSERT INTO dispute_case(id,order_id,initiator_id,disputed_quantity,reason,status,seller_deadline,opened_at,created_at,updated_at) VALUES (?,?,?,1,'FUNCTIONAL_DEFECT','OPEN',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", dispute.toString(), order.toString(), buyer.toString());
        HttpResponse<String> upload = multipart("/api/disputes/" + dispute + "/evidence", token(buyer), "failure.png", "image/png", PNG);
        assertThat(upload.statusCode()).isEqualTo(201);
        String evidenceId = field(upload.body(), "evidenceId");
        assertThat(get("/api/disputes/" + dispute + "/evidence/" + evidenceId + "/content", token(buyer)).statusCode()).isEqualTo(200);
        assertThat(get("/api/disputes/" + dispute + "/evidence/" + evidenceId + "/content", token(seller)).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE dispute_case SET assigned_admin_id=? WHERE id=?", assignedAdmin.toString(), dispute.toString());
        assertThat(get("/api/disputes/" + dispute + "/evidence/" + evidenceId + "/content", tokenWithRole(assignedAdmin, "ROLE_ADMIN")).statusCode()).isEqualTo(200);
        HttpResponse<String> denied = get("/api/disputes/" + dispute + "/evidence/" + evidenceId + "/content", token(other));
        HttpResponse<String> unassignedAdminResponse = get("/api/disputes/" + dispute + "/evidence/" + evidenceId + "/content", tokenWithRole(unassignedAdmin, "ROLE_ADMIN"));
        HttpResponse<String> missing = get("/api/disputes/" + dispute + "/evidence/" + UUID.randomUUID() + "/content", token(other));
        assertThat(denied.statusCode()).isEqualTo(404);
        assertThat(unassignedAdminResponse.statusCode()).isEqualTo(404);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(denied.body()).isEqualTo(missing.body());
        assertThat(unassignedAdminResponse.body()).isEqualTo(missing.body());
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

    @Test
    void disputeJsonNullMalformedAndUnknownFieldsAreUtf8Json400() throws Exception {
        UUID buyer = user("json-buyer");
        UUID admin = user("json-admin");
        UUID randomOrder = UUID.randomUUID();
        UUID randomCase = UUID.randomUUID();
        String[][] requests = {
            {"/api/orders/" + randomOrder + "/disputes", token(buyer), "{\"disputedQuantity\":1,\"reason\":\"FUNCTIONAL_DEFECT\",\"unknown\":true}"},
            {"/api/disputes/" + randomCase + "/responses", token(buyer), "null"},
            {"/api/disputes/" + randomCase + "/assignments", token(admin), "{\"adminId\":\"" + admin + "\",\"unknown\":true}"},
            {"/api/disputes/" + randomCase + "/decisions", token(admin), "{"},
        };
        for (String[] request : requests) {
            HttpResponse<String> response = jsonPost(request[0], request[1], "json-" + UUID.randomUUID(), request[2]);
            assertThat(response.statusCode()).as(request[0]).isEqualTo(400);
            assertThat(response.headers().firstValue("Content-Type")).contains("application/json; charset=UTF-8");
            assertThat(response.body()).startsWith("{").contains("error");
        }
    }

    @Test
    void evidenceContentReturnsStoredBytesAndContentTypeAndMinioReadFailureIs503() throws Exception {
        UUID buyer = user("content-buyer"); UUID seller = user("content-seller");
        UUID listing = listing(seller); UUID order = order(listing, buyer, seller); UUID dispute = dispute(order, buyer);
        HttpResponse<String> upload = multipart("/api/disputes/" + dispute + "/evidence", token(buyer), "content.png", "image/png", PNG);
        assertThat(upload.statusCode()).isEqualTo(201);
        String evidenceId = field(upload.body(), "evidenceId");
        HttpResponse<byte[]> content = getBytes("/api/disputes/" + dispute + "/evidence/" + evidenceId + "/content", token(buyer));
        assertThat(content.statusCode()).isEqualTo(200);
        assertThat(content.body()).containsExactly(PNG);
        assertThat(content.headers().firstValue("Content-Type")).contains("image/png");

        MINIO_PROXY.setConnectionCut(true);
        try {
            assertThat(getBytes("/api/disputes/" + dispute + "/evidence/" + evidenceId + "/content", token(buyer)).statusCode()).isEqualTo(503);
        } finally { MINIO_PROXY.setConnectionCut(false); }
    }

    @Test
    void acceptsEachSupportedEvidenceSignatureAndTikaType() throws Exception {
        UUID buyer = user("types-buyer"); UUID seller = user("types-seller");
        UUID dispute = dispute(order(listing(seller), buyer, seller), buyer);
        Object[][] fixtures = {
            {"photo.jpg", "image/jpeg", JPEG}, {"photo.webp", "image/webp", WEBP},
            {"document.pdf", "application/pdf", PDF}, {"clip.mp4", "video/mp4", MP4}
        };
        for (Object[] fixture : fixtures) {
            HttpResponse<String> response = multipart("/api/disputes/" + dispute + "/evidence", token(buyer),
                (String) fixture[0], (String) fixture[1], (byte[]) fixture[2]);
            assertThat(response.statusCode()).as((String) fixture[0]).isEqualTo(201);
            assertThat(response.body()).contains("\"mediaType\":\"" + fixture[1] + "\"");
        }
    }

    @Test
    void rejectsEachTypeAtItsOwnLimitPlusOneUsingStreamingBodies() throws Exception {
        UUID buyer = user("bounds-buyer"); UUID seller = user("bounds-seller");
        UUID dispute = dispute(order(listing(seller), buyer, seller), buyer);
        Object[][] fixtures = {
            {"bound.jpg", "image/jpeg", JPEG, 10L * 1024 * 1024 + 1},
            {"bound.webp", "image/webp", WEBP, 10L * 1024 * 1024 + 1},
            {"bound.pdf", "application/pdf", PDF, 20L * 1024 * 1024 + 1},
            {"bound.mp4", "video/mp4", MP4, 100L * 1024 * 1024 + 1}
        };
        for (Object[] fixture : fixtures) {
            HttpResponse<String> response = streamingMultipart("/api/disputes/" + dispute + "/evidence", token(buyer),
                (String) fixture[0], (String) fixture[1], (byte[]) fixture[2], (Long) fixture[3]);
            assertThat(response.statusCode()).as((String) fixture[0]).isEqualTo(400);
        }
    }

    @Test
    void createUsesSameResourceReplayAndRejectsCrossOrderKeyReuse() throws Exception {
        UUID buyer = user("create-buyer"); UUID seller = user("create-seller");
        UUID firstOrder = order(listing(seller), buyer, seller);
        UUID secondOrder = order(listing(seller), buyer, seller);
        String body = "{\"disputedQuantity\":1,\"reason\":\"FUNCTIONAL_DEFECT\"}";
        HttpResponse<String> first = jsonPost("/api/orders/" + firstOrder + "/disputes", token(buyer), "create-key", body);
        HttpResponse<String> replay = jsonPost("/api/orders/" + firstOrder + "/disputes", token(buyer), "create-key", body);
        HttpResponse<String> crossOrder = jsonPost("/api/orders/" + secondOrder + "/disputes", token(buyer), "create-key", body);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(crossOrder.statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispute_case WHERE order_id=?", Integer.class, firstOrder.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispute_case WHERE order_id=?", Integer.class, secondOrder.toString())).isZero();
    }

    @Test
    void sellerResponseReplayReturnsPersistedResponseAndCrossCaseReuseConflicts() throws Exception {
        UUID buyer = user("respond-buyer"); UUID seller = user("respond-seller");
        UUID firstOrder = order(listing(seller), buyer, seller);
        UUID firstCase = UUID.fromString(field(jsonPost("/api/orders/" + firstOrder + "/disputes", token(buyer), "open-respond-" + UUID.randomUUID(), "{\"disputedQuantity\":1,\"reason\":\"FUNCTIONAL_DEFECT\"}").body(), "disputeId"));
        UUID secondOrder = order(listing(seller), buyer, seller);
        UUID secondCase = UUID.fromString(field(jsonPost("/api/orders/" + secondOrder + "/disputes", token(buyer), "open-respond-" + UUID.randomUUID(), "{\"disputedQuantity\":1,\"reason\":\"FUNCTIONAL_DEFECT\"}").body(), "disputeId"));
        String body = "{\"response\":\"已核实并接受退货\"}";
        HttpResponse<String> first = jsonPost("/api/disputes/" + firstCase + "/responses", token(seller), "respond-key", body);
        HttpResponse<String> replay = jsonPost("/api/disputes/" + firstCase + "/responses", token(seller), "respond-key", body);
        HttpResponse<String> crossCase = jsonPost("/api/disputes/" + secondCase + "/responses", token(seller), "respond-key", body);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(first.body()).contains("已核实并接受退货");
        assertThat(jdbc.queryForObject("SELECT seller_response FROM dispute_case WHERE id=?", String.class, firstCase.toString())).isEqualTo("已核实并接受退货");
        assertThat(crossCase.statusCode()).isEqualTo(409);
    }

    @Test
    void decisionReplayIsStableAndCrossCaseKeyReuseConflicts() throws Exception {
        UUID buyer = user("decision-buyer"); UUID seller = user("decision-seller"); UUID admin = user("decision-admin");
        UUID firstOrder = order(listing(seller), buyer, seller);
        UUID firstCase = UUID.fromString(field(jsonPost("/api/orders/" + firstOrder + "/disputes", token(buyer), "open-decision-" + UUID.randomUUID(), "{\"disputedQuantity\":1,\"reason\":\"FUNCTIONAL_DEFECT\"}").body(), "disputeId"));
        UUID secondOrder = order(listing(seller), buyer, seller);
        UUID secondCase = UUID.fromString(field(jsonPost("/api/orders/" + secondOrder + "/disputes", token(buyer), "open-decision-" + UUID.randomUUID(), "{\"disputedQuantity\":1,\"reason\":\"FUNCTIONAL_DEFECT\"}").body(), "disputeId"));
        String body = "{\"decision\":\"REFUND_ONLY\",\"approvedQuantity\":1}";
        HttpResponse<String> first = jsonPost("/api/disputes/" + firstCase + "/decisions", tokenWithRole(admin, "ROLE_ADMIN"), "decision-key", body);
        HttpResponse<String> replay = jsonPost("/api/disputes/" + firstCase + "/decisions", tokenWithRole(admin, "ROLE_ADMIN"), "decision-key", body);
        HttpResponse<String> crossCase = jsonPost("/api/disputes/" + secondCase + "/decisions", tokenWithRole(admin, "ROLE_ADMIN"), "decision-key", body);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(crossCase.statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT status FROM dispute_case WHERE id=?", String.class, firstCase.toString())).isEqualTo("RESOLVED");
    }

    private UUID user(String name) { UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO campus_user(id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", id.toString(), id+"@"+name+".stu.example.edu.cn", "hash"); return id; }
    private UUID listing(UUID seller) { UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,status,created_at,updated_at) VALUES (?,?, '键盘','二手','电子',100,0,'SOLD_OUT',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", id.toString(), seller.toString()); return id; }
    private UUID order(UUID listing, UUID buyer, UUID seller) { UUID id=UUID.randomUUID(); Instant t0=Instant.now().minusSeconds(3600); jdbc.update("INSERT INTO trade_order(id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,unit_price_fen,quantity,total_amount_fen,t0,acceptance_deadline,trial_deadline,paid_amount_fen,status,created_at,updated_at) VALUES (?,?,?,?, '键盘','二手',100,1,100,?,?,?,100,'AFTERSALE_WINDOW',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", id.toString(), buyer.toString(), seller.toString(), listing.toString(), java.sql.Timestamp.from(t0), java.sql.Timestamp.from(t0.plusSeconds(72*3600)), java.sql.Timestamp.from(t0.plusSeconds(7*86400))); return id; }
    private UUID dispute(UUID order, UUID buyer) { UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO dispute_case(id,order_id,initiator_id,disputed_quantity,reason,status,seller_deadline,opened_at,created_at,updated_at) VALUES (?,?,?,1,'FUNCTIONAL_DEFECT','OPEN',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", id.toString(), order.toString(), buyer.toString()); return id; }
    private String token(UUID user) { return jwt.issue(new AuthenticatedUser(user, Set.of("ROLE_USER"))); }
    private String tokenWithRole(UUID user, String role) { return jwt.issue(new AuthenticatedUser(user, Set.of(role))); }
    private HttpResponse<String> get(String path, String token) throws Exception { return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization","Bearer "+token).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
    private HttpResponse<byte[]> getBytes(String path, String token) throws Exception { return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization","Bearer "+token).GET().build(), HttpResponse.BodyHandlers.ofByteArray()); }
    private HttpResponse<String> jsonPost(String path, String token, String key, String body) throws Exception { return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization","Bearer "+token).header("Idempotency-Key", key).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
    private HttpResponse<String> multipart(String path,String token,String filename,String type,byte[] bytes) throws Exception { String b="----campus"+UUID.randomUUID(); byte[] p=("--"+b+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\nContent-Type: "+type+"\r\n\r\n").getBytes(StandardCharsets.UTF_8), s=("\r\n--"+b+"--\r\n").getBytes(StandardCharsets.UTF_8), all=new byte[p.length+bytes.length+s.length]; System.arraycopy(p,0,all,0,p.length); System.arraycopy(bytes,0,all,p.length,bytes.length); System.arraycopy(s,0,all,p.length+bytes.length,s.length); return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization","Bearer "+token).header("Content-Type","multipart/form-data; boundary="+b).POST(HttpRequest.BodyPublishers.ofByteArray(all)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
    private HttpResponse<String> streamingMultipart(String path, String token, String filename, String type, byte[] signature, long size) throws Exception {
        String boundary = "----campus" + UUID.randomUUID();
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
            + "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        InputStream body = new SequenceInputStream(new ByteArrayInputStream(prefix),
            new SequenceInputStream(new FixedLengthZeroStream(signature, size), new ByteArrayInputStream(suffix)));
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + token).header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> body)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private static final class FixedLengthZeroStream extends InputStream {
        private final byte[] prefix; private final long total; private long position;
        private FixedLengthZeroStream(byte[] prefix, long total) { this.prefix = prefix; this.total = total; }
        @Override public int read() { if (position >= total) return -1; return valueAt(position++); }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (position >= total) return -1; int count = (int) Math.min(length, total - position);
            for (int i = 0; i < count; i++) buffer[offset + i] = (byte) valueAt(position++); return count;
        }
        private int valueAt(long index) { return index < prefix.length ? prefix[(int) index] & 0xff : 0; }
    }
    private static String field(String json,String field){java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\\""+field+"\\\"\\s*:\\s*\\\"([^\\\"]+)").matcher(json); if(!m.find()) throw new AssertionError(json); return m.group(1);}
}
