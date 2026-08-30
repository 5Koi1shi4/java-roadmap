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
}
