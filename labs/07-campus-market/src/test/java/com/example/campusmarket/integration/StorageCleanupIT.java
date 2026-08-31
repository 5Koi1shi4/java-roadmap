package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.storage.PrivateObjectStorage;
import com.example.campusmarket.storage.StorageCleanupScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = CampusMarketApplication.class)
@ActiveProfiles("local")
class StorageCleanupIT extends SharedContainers {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PrivateObjectStorage storage;
    @Autowired private StorageCleanupScheduler scheduler;

    @Test
    void expiredLeaseIsTakenOverAndStaleOwnerCannotComplete() {
        String key = "cleanup-test/" + UUID.randomUUID();
        storage.put(key, new ByteArrayInputStream("temporary".getBytes(StandardCharsets.UTF_8)), 9, "text/plain");
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO storage_cleanup_task (id,cleanup_business_key,object_key,owner_id,claim_token,lease_until,attempt_count,status,run_after,created_at,updated_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP(6)-INTERVAL 1 MINUTE,1,'PROCESSING',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            id, "cleanup-test:" + id, key, "old-owner", "old-token");

        assertThat(scheduler.runOnce(10)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM storage_cleanup_task WHERE id=?", String.class, id)).isEqualTo("COMPLETED");
        assertThat(jdbc.update("UPDATE storage_cleanup_task SET status='COMPLETED' WHERE id=? AND owner_id=? AND claim_token=?",
            id, "old-owner", "old-token")).isZero();
    }

    @Test
    void deleteFailureReleasesTaskForRetry() {
        String key = "cleanup-failure/" + UUID.randomUUID();
        storage.put(key, new ByteArrayInputStream("temporary".getBytes(StandardCharsets.UTF_8)), 9, "text/plain");
        String id = insertPending(key, "failure");
        MINIO_PROXY.setConnectionCut(true);
        try {
            assertThat(scheduler.runOnce(10)).isZero();
        } finally {
            MINIO_PROXY.setConnectionCut(false);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM storage_cleanup_task WHERE id=?", String.class, id)).isEqualTo("PENDING");
        jdbc.update("UPDATE storage_cleanup_task SET run_after=CURRENT_TIMESTAMP(6) WHERE id=?", id);
        assertThat(scheduler.runOnce(10)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM storage_cleanup_task WHERE id=?", String.class, id)).isEqualTo("COMPLETED");
    }

    @Test
    void expiredUploadSessionCreatesUniqueCleanup() {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_user (id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            user.toString(), user + "@stu.example.edu.cn", "hash");
        String key = "expired-session/" + UUID.randomUUID();
        storage.put(key, new ByteArrayInputStream("temporary".getBytes(StandardCharsets.UTF_8)), 9, "text/plain");
        String sessionId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO object_upload_session (id,submitted_by,purpose,object_key,status,expires_at,created_at,updated_at) VALUES (?,?, 'LISTING_MEDIA',?,'OPEN',CURRENT_TIMESTAMP(6)-INTERVAL 1 MINUTE,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            sessionId, user.toString(), key);
        assertThat(scheduler.runOnce(10)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM object_upload_session WHERE id=?", String.class, sessionId)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM storage_cleanup_task WHERE cleanup_business_key=?", Integer.class,
            "listing-upload:" + sessionId)).isEqualTo(1);
    }

    @Test
    void concurrentSchedulersClaimOneTask() throws Exception {
        String key = "cleanup-concurrent/" + UUID.randomUUID();
        storage.put(key, new ByteArrayInputStream("temporary".getBytes(StandardCharsets.UTF_8)), 9, "text/plain");
        insertPending(key, "concurrent");
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> scheduler.runOnce(1));
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> scheduler.runOnce(1));
        assertThat(first.get(20, TimeUnit.SECONDS) + second.get(20, TimeUnit.SECONDS)).isEqualTo(1);
    }

    private String insertPending(String key, String suffix) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO storage_cleanup_task (id,cleanup_business_key,object_key,status,run_after,created_at,updated_at) VALUES (?,?,?,'PENDING',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            id, "cleanup-" + suffix + ":" + id, key);
        return id;
    }
}
