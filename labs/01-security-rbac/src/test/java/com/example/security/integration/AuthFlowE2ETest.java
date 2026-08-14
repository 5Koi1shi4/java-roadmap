package com.example.security.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class AuthFlowE2ETest {

    private static final String TEST_JWT_SECRET = Base64.getEncoder().encodeToString(new byte[32]);

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.46")
            .withDatabaseName("security_rbac")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("jwt.secret", () -> TEST_JWT_SECRET);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @LocalServerPort
    private int port;

    @BeforeEach
    void seedAdmin() {
        jdbc.update("DELETE FROM refresh_token");
        jdbc.update("DELETE FROM sys_role_permission");
        jdbc.update("DELETE FROM sys_user_role");
        jdbc.update("DELETE FROM sys_permission");
        jdbc.update("DELETE FROM sys_role");
        jdbc.update("DELETE FROM sys_user");

        jdbc.update("INSERT INTO sys_user (username, password_hash, enabled) VALUES (?, ?, ?)",
                "admin", new BCryptPasswordEncoder().encode("correct-password"), true);
        jdbc.update("INSERT INTO sys_role (code, name) VALUES (?, ?)", "ADMIN", "Administrator");
        jdbc.update("INSERT INTO sys_permission (code, name) VALUES (?, ?)", "system:user:read", "Read users");

        Long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = 'admin'", Long.class);
        Long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE code = 'ADMIN'", Long.class);
        Long permissionId = jdbc.queryForObject("SELECT id FROM sys_permission WHERE code = 'system:user:read'", Long.class);
        jdbc.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
        jdbc.update("INSERT INTO sys_role_permission (role_id, permission_id) VALUES (?, ?)", roleId, permissionId);
    }

    @Test
    void logsInAccessesProtectedResourceRefreshesAndLogsOut() {
        ResponseEntity<TokenPair> login = post("/api/auth/login",
                Map.of("username", "admin", "password", "correct-password"), TokenPair.class);

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody()).isNotNull();
        assertThat(login.getBody().accessToken()).isNotBlank();
        assertThat(login.getBody().refreshToken()).isNotBlank();

        ResponseEntity<String> users = rest.exchange(url("/api/admin/users"), HttpMethod.GET,
                new HttpEntity<>(bearer(login.getBody().accessToken())), String.class);
        assertThat(users.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(users.getBody()).contains("admin");

        ResponseEntity<TokenPair> refresh = post("/api/auth/refresh",
                Map.of("refreshToken", login.getBody().refreshToken()), TokenPair.class);
        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refresh.getBody()).isNotNull();
        assertThat(refresh.getBody().accessToken()).isNotBlank();
        assertThat(refresh.getBody().refreshToken()).isNotEqualTo(login.getBody().refreshToken());

        ResponseEntity<String> rejectedOriginalRefresh = post("/api/auth/refresh",
                Map.of("refreshToken", login.getBody().refreshToken()), String.class);
        assertThat(rejectedOriginalRefresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> usersWithRotatedAccessToken = rest.exchange(url("/api/admin/users"), HttpMethod.GET,
                new HttpEntity<>(bearer(refresh.getBody().accessToken())), String.class);
        assertThat(usersWithRotatedAccessToken.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Void> logout = post("/api/auth/logout",
                Map.of("refreshToken", refresh.getBody().refreshToken()), Void.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> rejectedRefresh = post("/api/auth/refresh",
                Map.of("refreshToken", refresh.getBody().refreshToken()), String.class);
        assertThat(rejectedRefresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void allowsOnlyOneConcurrentRefreshForTheSameToken() throws Exception {
        ResponseEntity<TokenPair> login = post("/api/auth/login",
                Map.of("username", "admin", "password", "correct-password"), TokenPair.class);
        assertThat(login.getBody()).isNotNull();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<ResponseEntity<String>> first = executor.submit(() -> concurrentRefresh(login.getBody().refreshToken(), ready, start));
            Future<ResponseEntity<String>> second = executor.submit(() -> concurrentRefresh(login.getBody().refreshToken(), ready, start));

            ready.await();
            start.countDown();

            HttpStatus firstStatus = (HttpStatus) first.get().getStatusCode();
            HttpStatus secondStatus = (HttpStatus) second.get().getStatusCode();
            assertThat(firstStatus).isIn(HttpStatus.OK, HttpStatus.UNAUTHORIZED);
            assertThat(secondStatus).isIn(HttpStatus.OK, HttpStatus.UNAUTHORIZED);
            assertThat(java.util.List.of(firstStatus, secondStatus))
                    .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.UNAUTHORIZED);
        } finally {
            executor.shutdownNow();
        }
    }

    private <T> ResponseEntity<T> post(String path, Object body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(url(path), new HttpEntity<>(body, headers), responseType);
    }

    private HttpHeaders bearer(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }

    private ResponseEntity<String> concurrentRefresh(
            String refreshToken,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        try {
            start.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        return post("/api/auth/refresh", Map.of("refreshToken", refreshToken), String.class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private record TokenPair(String accessToken, String refreshToken) {
    }
}
