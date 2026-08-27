package com.example.files.integration;

import com.example.files.application.cleanup.ClaimedCleanup;
import com.example.files.infrastructure.persistence.JdbcCleanupTaskRepository;
import com.example.files.infrastructure.persistence.JdbcUploadSessionRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** 真实 MySQL 验证清理任务 lease takeover 与 claim token fencing。 */
class CleanupLeaseIT extends SharedMySqlContainer {
    private static JdbcTemplate jdbc;
    private static JdbcCleanupTaskRepository tasks;
    private static JdbcUploadSessionRepository sessions;
    @BeforeAll static void prepare() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        tasks = new JdbcCleanupTaskRepository(jdbc);
        sessions = new JdbcUploadSessionRepository(jdbc);
    }
    @BeforeEach void clean() { jdbc.update("DELETE FROM storage_cleanup_task"); }
    @Test void oldOwnerCannotCompleteAfterLeaseTakeover() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO storage_cleanup_task(task_id,task_type,target_id,target_generation,object_key,status,available_at,attempt_count,created_at) VALUES(?,?,?,?,?,'NEW',CURRENT_TIMESTAMP(6),0,CURRENT_TIMESTAMP(6))",
            id.toString(), "TEMP_OBJECT", UUID.randomUUID().toString(), 1, "tmp/00000000-0000-0000-0000-000000000001");
        ClaimedCleanup first = tasks.claimOne("worker-a", Duration.ofSeconds(30));
        jdbc.update("UPDATE storage_cleanup_task SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE task_id=?", id.toString());
        ClaimedCleanup second = tasks.claimOne("worker-b", Duration.ofSeconds(30));
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(tasks.complete(first.taskId(), first.claimToken())).isFalse();
        assertThat(tasks.complete(second.taskId(), second.claimToken())).isTrue();
    }

    @Test void recoveryClaimRequiresExpiredTtlAndMatchingOwnerAndTempKey() {
        UUID sessionId = UUID.randomUUID();
        UUID oldOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        String tempKey = "tmp/" + sessionId;
        jdbc.update("INSERT INTO upload_session(session_id,uploader_id,temp_key,owner_token,status,lease_until,expires_at,original_name,declared_type,created_at,updated_at) "
                + "VALUES(?,?,?,?, 'VALIDATED', TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)), "
                + "TIMESTAMPADD(MINUTE,5,CURRENT_TIMESTAMP(6)), 'x.txt','text/plain',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            sessionId.toString(), 1L, tempKey, oldOwner.toString());

        assertThat(sessions.claimExpiredForRecovery(sessionId, oldOwner, tempKey, newOwner, Duration.ofSeconds(30))).isFalse();

        jdbc.update("UPDATE upload_session SET expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE session_id=?",
            sessionId.toString());
        assertThat(sessions.claimExpiredForRecovery(sessionId, oldOwner, tempKey + "-wrong", newOwner, Duration.ofSeconds(30))).isFalse();
        assertThat(sessions.claimExpiredForRecovery(sessionId, oldOwner, tempKey, newOwner, Duration.ofSeconds(30))).isTrue();
        assertThat(jdbc.queryForObject("SELECT owner_token FROM upload_session WHERE session_id=?", String.class,
            sessionId.toString())).isEqualTo(newOwner.toString());
        jdbc.update("DELETE FROM upload_session WHERE session_id=?", sessionId.toString());
    }
}
