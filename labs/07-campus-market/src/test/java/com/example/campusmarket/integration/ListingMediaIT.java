package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.identity.application.AuthenticatedUser;
import com.example.campusmarket.identity.infrastructure.JwtService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = CampusMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ListingMediaIT extends SharedContainers {
    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @LocalServerPort
    private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
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
        assertThat(getBytes("/api/listings/" + listingId + "/media/" + UUID.randomUUID(), token(seller)).statusCode()).isEqualTo(404);
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

    private UUID createUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_user (id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            id.toString(), id + "@stu.example.edu.cn", "$2a$10$7EqJtq98hPqEX7fNZaFWoO3M8fA5M9j3rQf6Jt2f7hM2yJ0f7vQzK");
        return id;
    }

    private String token(UUID user) {
        return jwtService.issue(new AuthenticatedUser(user, Set.of("ROLE_USER")));
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
