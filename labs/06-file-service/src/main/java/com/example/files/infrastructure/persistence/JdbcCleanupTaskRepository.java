package com.example.files.infrastructure.persistence;

import com.example.files.application.cleanup.CleanupTaskRepository;
import com.example.files.domain.UploadSession;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/** 临时对象清理任务 JDBC 实现；唯一目标键保证补偿入队幂等。 */
public final class JdbcCleanupTaskRepository implements CleanupTaskRepository {
    private final JdbcTemplate jdbc;

    public JdbcCleanupTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void enqueueTemp(UploadSession session, String tempKey) {
        if (session == null || tempKey == null || tempKey.isBlank()) throw new IllegalArgumentException("invalid cleanup target");
        try {
            jdbc.update("INSERT INTO storage_cleanup_task(task_id,task_type,target_id,target_generation,object_key,"
                    + "source_session_id,status,available_at,attempt_count,created_at) VALUES(?,?,?,?,?,?, 'NEW',CURRENT_TIMESTAMP(6),0,CURRENT_TIMESTAMP(6))",
                UUID.randomUUID().toString(), "TEMP_OBJECT", session.sessionId().toString(), 1L, tempKey,
                session.sessionId().toString());
        } catch (DuplicateKeyException ignored) {
            // 同一会话和代次的清理任务只保留一条。
        }
    }

    @Override
    public void enqueueTempIfEligible(UUID sessionId) {
        if (sessionId == null) throw new IllegalArgumentException("sessionId must not be null");
        UploadSession session = new JdbcUploadSessionRepository(jdbc).find(sessionId).orElse(null);
        if (session != null) enqueueTemp(session, session.tempKey());
    }

    @Override
    public void enqueueBlob(com.example.files.domain.StoredBlob blob) {
        if (blob == null) throw new IllegalArgumentException("blob must not be null");
        try {
            jdbc.update("INSERT INTO storage_cleanup_task(task_id,task_type,target_id,target_generation,object_key,"
                    + "status,available_at,attempt_count,created_at) VALUES(?,?,?,?,?,'NEW',CURRENT_TIMESTAMP(6),0,CURRENT_TIMESTAMP(6))",
                UUID.randomUUID().toString(), "BLOB_OBJECT", Long.toString(blob.id()), blob.generation(), blob.objectKey());
        } catch (DuplicateKeyException ignored) {
            // 同一 Blob 代次只保留一条清理任务。
        }
    }
}
