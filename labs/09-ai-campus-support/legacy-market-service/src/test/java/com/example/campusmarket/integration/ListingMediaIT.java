package com.example.campusmarket.integration;

import com.example.campusmarket.legacy.LegacyMarketApplication;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.example.campusmarket.storage.UploadBindingHook;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = LegacyMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ListingMediaIT extends SharedContainers {
    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    private static final byte[] JPEG = Base64.getDecoder().decode(
        "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////2wBDAf//////////////////////////////////////////////////////////////////////////////////////wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAX/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIQAxAAAAH/AP/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAT8Af//Z");
    private static final byte[] WEBP = Base64.getDecoder().decode(
        "UklGRiIAAABXRUJQVlA4IBAAAADQAQCdASoBAAEAAUAmJaQAA3AA/v89WAAAAAA=");

    @LocalServerPort
    private int port;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private UploadBindingHook bindingHook;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @Order(1)
    void ownerCanUploadAndPublishPrivateMedia() throws Exception {
        UUID seller = createUser();
        String token = token(seller);
        HttpResponse<String> created = request("POST", "/api/listings", token,
            "{\"title\":\"教材\",\"description\":\"九成新\",\"category\":\"教材\",\"unitPriceFen\":3500,\"availableQuantity\":6}", "application/json");
        assertThat(created.statusCode()).isEqualTo(201);
        String listingId = field(created.body(), "id");

        HttpResponse<String> upload = multipart("/api/listings/" + listingId + "/media", token,
            "cover.png", "image/png", PNG);
        assertThat(upload.statusCode()).isEqualTo(201);
        String mediaId = field(upload.body(), "id");

        HttpResponse<String> published = request("POST", "/api/listings/" + listingId + "/publish", token, "", null);
        assertThat(published.statusCode()).isEqualTo(200);
        HttpResponse<byte[]> content = getBytes("/api/listings/" + listingId + "/media/" + mediaId, token);
        assertThat(content.statusCode()).isEqualTo(200);
        assertThat(content.body()).containsExactly(PNG);
    }

    @Test
    @Order(2)
    void rejectsDeclaredTypeOrExtensionForgeryAndProtectsDraftOwner() throws Exception {
        UUID seller = createUser();
        String token = token(seller);
        String listingId = field(request("POST", "/api/listings", token,
            "{\"title\":\"草稿\",\"description\":\"描述\",\"category\":\"教材\",\"unitPriceFen\":100,\"availableQuantity\":1}", "application/json").body(), "id");
        assertThat(multipart("/api/listings/" + listingId + "/media", token, "cover.jpg", "image/png", PNG).statusCode()).isEqualTo(400);
        assertThat(multipart("/api/listings/" + listingId + "/media", token, "cover.png", "image/png",
            "not-a-png".getBytes(StandardCharsets.UTF_8)).statusCode()).isEqualTo(400);
        String mediaId = field(multipart("/api/listings/" + listingId + "/media", token, "cover.png", "image/png", PNG).body(), "id");
        assertThat(getBytes("/api/listings/" + listingId + "/media/" + mediaId, token(createUser())).statusCode()).isEqualTo(404);
    }

    @Test
    @Order(3)
    void rejectsTenMiBPlusOneWithoutReadingBeyondBound() throws Exception {
        UUID seller = createUser();
        String token = token(seller);
        String listingId = field(request("POST", "/api/listings", token,
            "{\"title\":\"大文件\",\"description\":\"描述\",\"category\":\"教材\",\"unitPriceFen\":100,\"availableQuantity\":1}", "application/json").body(), "id");
        byte[] oversized = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy(PNG, 0, oversized, 0, PNG.length);
        assertThat(multipart("/api/listings/" + listingId + "/media", token, "cover.png", "image/png", oversized).statusCode()).isEqualTo(400);
    }

    @Test
    @Order(4)
    void concurrentTenthMediaIsRejectedAndCleaned() throws Exception {
        UUID seller = createUser();
        String token = token(seller);
        String listingId = newListing(token);
        for (int i = 0; i < 9; i++) {
            assertThat(multipart("/api/listings/" + listingId + "/media", token, "cover" + i + ".png", "image/png", PNG).statusCode()).isEqualTo(201);
        }
        CompletableFuture<HttpResponse<String>> one = CompletableFuture.supplyAsync(() -> sendMultipartUnchecked(listingId, token, "tenth-a.png"));
        CompletableFuture<HttpResponse<String>> two = CompletableFuture.supplyAsync(() -> sendMultipartUnchecked(listingId, token, "tenth-b.png"));
        int first = one.get(20, TimeUnit.SECONDS).statusCode();
        int second = two.get(20, TimeUnit.SECONDS).statusCode();
        assertThat(java.util.List.of(first, second)).containsExactly(400, 400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM listing_media WHERE listing_id=?", Integer.class, listingId)).isEqualTo(9);
        var failedSessions = jdbc.query("SELECT id, object_key FROM object_upload_session WHERE submitted_by=? AND status='ABORTED' ORDER BY created_at DESC LIMIT 2",
            (rs, rowNum) -> new String[]{rs.getString("id"), rs.getString("object_key")}, seller.toString());
        assertThat(failedSessions).hasSize(2);
        assertThat(failedSessions.get(0)[1]).isNotEqualTo(failedSessions.get(1)[1]);
        for (String[] session : failedSessions) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM storage_cleanup_task WHERE cleanup_business_key=?", Integer.class,
                "listing-upload:" + session[0])).isEqualTo(1);
            assertThat(session[1]).doesNotContain(listingId, seller.toString(), "tenth-a.png", "tenth-b.png", "图片");
        }
    }

    @Test
    @Order(5)
    void acceptsExactlyTenMiBBasedOnBytes() throws Exception {
        UUID seller = createUser();
        String token = token(seller);
        String listingId = field(request("POST", "/api/listings", token,
            "{\"title\":\"边界\",\"description\":\"描述\",\"category\":\"教材\",\"unitPriceFen\":100,\"availableQuantity\":1}", "application/json").body(), "id");
        byte[] exact = new byte[10 * 1024 * 1024];
        System.arraycopy(PNG, 0, exact, 0, PNG.length);
        assertThat(multipart("/api/listings/" + listingId + "/media", token, "cover.png", "image/png", exact).statusCode()).isEqualTo(201);
    }

    @Test
    @Order(6)
    void acceptsJpegAndWebpBySignature() throws Exception {
        UUID seller = createUser();
        String token = token(seller);
        String jpegListing = newListing(token);
        String webpListing = newListing(token);
        assertThat(multipart("/api/listings/" + jpegListing + "/media", token, "cover.jpg", "image/jpeg", JPEG).statusCode()).isEqualTo(201);
        assertThat(multipart("/api/listings/" + webpListing + "/media", token, "cover.webp", "image/webp", WEBP).statusCode()).isEqualTo(201);
    }

    @Test
    @Order(98)
    void bindingFailureRollsBackMediaAndLeavesOneCleanup() throws Exception {
        UUID seller = createUser();
        String token = token(seller);
        String listingId = newListing(token);
        doThrow(new IllegalStateException("injected binding failure")).when(bindingHook)
            .afterMediaInserted(any(), any());
        assertThat(multipart("/api/listings/" + listingId + "/media", token, "rollback.png", "image/png", PNG).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM listing_media WHERE listing_id=?", Integer.class, listingId)).isZero();
        String sessionId = jdbc.queryForObject("SELECT id FROM object_upload_session WHERE submitted_by=? ORDER BY created_at DESC LIMIT 1",
            String.class, seller.toString());
        assertThat(jdbc.queryForObject("SELECT status FROM object_upload_session WHERE id=?", String.class, sessionId)).isEqualTo("ABORTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM storage_cleanup_task WHERE cleanup_business_key=?", Integer.class,
            "listing-upload:" + sessionId)).isEqualTo(1);
    }

    @Test
    @Order(99)
    void minioProxyDisconnectMapsTo503() throws Exception {
        UUID seller = createUser();
        String token = token(seller);
        String listingId = newListing(token);
        MINIO_PROXY.setConnectionCut(true);
        try {
            assertThat(multipart("/api/listings/" + listingId + "/media", token, "down.png", "image/png", PNG).statusCode()).isEqualTo(503);
        } finally {
            MINIO_PROXY.setConnectionCut(false);
        }
    }

    private String newListing(String token) throws Exception {
        return field(request("POST", "/api/listings", token,
            "{\"title\":\"图片\",\"description\":\"描述\",\"category\":\"教材\",\"unitPriceFen\":100,\"availableQuantity\":1}", "application/json").body(), "id");
    }

    private UUID createUser() {
        return UUID.randomUUID();
    }

    private String token(UUID user) {
        return ResourceServerTestSupport.token(user, Set.of("ROLE_USER"));
    }

    private HttpResponse<String> request(String method, String path, String token, String body, String contentType) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + token);
        if (contentType != null) builder.header("Content-Type", contentType);
        HttpRequest.BodyPublisher publisher = body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        return client.send(("POST".equals(method) ? builder.POST(publisher) : builder.GET()).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> multipart(String path, String token, String filename, String type, byte[] bytes) throws Exception {
        String boundary = "----campus" + UUID.randomUUID();
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[prefix.length + bytes.length + suffix.length];
        System.arraycopy(prefix, 0, all, 0, prefix.length);
        System.arraycopy(bytes, 0, all, prefix.length, bytes.length);
        System.arraycopy(suffix, 0, all, prefix.length + bytes.length, suffix.length);
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(all)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> sendMultipartUnchecked(String listingId, String token, String filename) {
        try {
            return multipart("/api/listings/" + listingId + "/media", token, filename, "image/png", PNG);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private HttpResponse<byte[]> getBytes(String path, String token) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String field(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\\"" + field + "\\\"\\s*:\\s*\\\"([^\\\"]+)").matcher(json);
        if (!m.find()) throw new AssertionError("missing " + field + " in " + json);
        return m.group(1);
    }
}
