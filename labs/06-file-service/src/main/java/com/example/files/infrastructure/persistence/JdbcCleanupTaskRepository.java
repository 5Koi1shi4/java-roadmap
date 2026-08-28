package com.example.files.infrastructure.persistence;

import com.example.files.application.cleanup.CleanupTaskRepository;
import com.example.files.domain.UploadSession;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.time.Duration;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import com.example.files.application.cleanup.ClaimedCleanup;
import com.example.files.application.cleanup.CleanupTaskType;
import org.springframework.transaction.support.TransactionTemplate;

/** 临时对象清理任务 JDBC 实现；唯一目标键保证补偿入队幂等。 */
public final class JdbcCleanupTaskRepository implements CleanupTaskRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    public JdbcCleanupTaskRepository(JdbcTemplate jdbc, TransactionTemplate transactionTemplate) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.transactionTemplate = java.util.Objects.requireNonNull(transactionTemplate, "transactionTemplate");
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
            Integer eligible = jdbc.queryForObject("SELECT COUNT(*) FROM stored_blob WHERE id=? AND generation=? AND status IN ('PENDING_DELETE','DELETING') AND reference_count=0",
                Integer.class, blob.id(), blob.generation());
            if (!Integer.valueOf(1).equals(eligible)) return;
            jdbc.update("INSERT INTO storage_cleanup_task(task_id,task_type,target_id,target_generation,object_key,"
                    + "status,available_at,attempt_count,created_at) VALUES(?,?,?,?,?,'NEW',CURRENT_TIMESTAMP(6),0,CURRENT_TIMESTAMP(6))",
                UUID.randomUUID().toString(), "BLOB_OBJECT", Long.toString(blob.id()), blob.generation(), blob.objectKey());
        } catch (DuplicateKeyException ignored) {
            // 同一 Blob 代次只保留一条清理任务。
        }
    }

    @Override
    public void enqueueBlob(com.example.files.application.upload.BlobReservation.Granted reservation) {
        if (reservation == null) throw new IllegalArgumentException("reservation must not be null");
        var row = jdbc.query("SELECT id,generation,object_key FROM stored_blob WHERE id=?",
            (rs, index) -> new Object[]{rs.getLong(1), rs.getLong(2), rs.getString(3)}, reservation.blobId());
        if (row.isEmpty()) return;
        Object[] values = row.get(0);
        Integer eligible = jdbc.queryForObject("SELECT COUNT(*) FROM stored_blob WHERE id=? AND generation=? AND status IN ('PENDING_DELETE','DELETING') AND reference_count=0",
            Integer.class, values[0], values[1]);
        if (!Integer.valueOf(1).equals(eligible)) return;
        try {
            jdbc.update("INSERT INTO storage_cleanup_task(task_id,task_type,target_id,target_generation,object_key,"
                    + "status,available_at,attempt_count,created_at) VALUES(?,?,?,?,?,'NEW',CURRENT_TIMESTAMP(6),0,CURRENT_TIMESTAMP(6))",
                UUID.randomUUID().toString(), "BLOB_OBJECT", Long.toString((Long) values[0]), values[1], values[2]);
        } catch (DuplicateKeyException ignored) {
            // 同一 Blob 代次只保留一条清理任务。
        }
    }

    @Override
    public List<ClaimedCleanup> claimBatch(String owner, int batchSize, Duration lease) {
        if (owner == null || owner.isBlank() || owner.length() > 128 || batchSize <= 0 || batchSize > 50
            || lease == null || lease.isZero() || lease.isNegative()) throw new IllegalArgumentException("invalid cleanup claim arguments");
        List<ClaimedCleanup> result = new ArrayList<>();
        // 每次条件更新只争抢一行，短事务且不把存储 I/O 放在数据库事务内。
        for (int i = 0; i < batchSize; i++) {
            String id = jdbc.query("SELECT task_id FROM storage_cleanup_task WHERE (status='NEW' AND available_at<=CURRENT_TIMESTAMP(6)) "
                    + "OR (status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP(6)) ORDER BY available_at,task_id LIMIT 1",
                (rs, row) -> rs.getString(1)).stream().findFirst().orElse(null);
            if (id == null) break;
            String token = UUID.randomUUID().toString();
            int updated = jdbc.update("UPDATE storage_cleanup_task SET status='PROCESSING',owner=?,claim_token=?,"
                    + "lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)) WHERE task_id=? AND "
                    + "((status='NEW' AND available_at<=CURRENT_TIMESTAMP(6)) OR (status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP(6)))",
                owner, token, JdbcUploadSessionRepository.micros(lease), id);
            if (updated != 1) continue;
            ClaimedCleanup claimed = jdbc.query("SELECT task_id,task_type,target_id,target_generation,object_key,claim_token,attempt_count "
                    + "FROM storage_cleanup_task WHERE task_id=? AND status='PROCESSING' AND owner=? AND claim_token=?",
                (rs, row) -> new ClaimedCleanup(UUID.fromString(rs.getString("task_id")),
                    CleanupTaskType.valueOf(rs.getString("task_type")), rs.getString("target_id"),
                    rs.getLong("target_generation"), rs.getString("object_key"),
                    UUID.fromString(rs.getString("claim_token")), rs.getInt("attempt_count")), id, owner, token)
                .stream().findFirst().orElse(null);
            if (claimed != null) result.add(claimed);
        }
        return result;
    }

    @Override
    public boolean complete(UUID taskId, UUID claimToken) {
        requireToken(taskId, claimToken);
        return jdbc.update("UPDATE storage_cleanup_task SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP(6),"
                + "owner=NULL,claim_token=NULL,lease_until=NULL WHERE task_id=? AND status='PROCESSING' AND claim_token=?",
            taskId.toString(), claimToken.toString()) == 1;
    }

    @Override
    public boolean retry(UUID taskId, UUID claimToken, Duration delay, String error) {
        requireToken(taskId, claimToken);
        if (delay == null || delay.isZero() || delay.isNegative()) throw new IllegalArgumentException("delay must be positive");
        return jdbc.update("UPDATE storage_cleanup_task SET status='NEW',attempt_count=attempt_count+1,last_error=?,"
                + "available_at=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),owner=NULL,claim_token=NULL,lease_until=NULL "
                + "WHERE task_id=? AND status='PROCESSING' AND claim_token=?",
            safeError(error), JdbcUploadSessionRepository.micros(delay), taskId.toString(), claimToken.toString()) == 1;
    }

    @Override
    public boolean fail(UUID taskId, UUID claimToken, String error) {
        requireToken(taskId, claimToken);
        return jdbc.update("UPDATE storage_cleanup_task SET status='FAILED',attempt_count=attempt_count+1,last_error=?,"
                + "owner=NULL,claim_token=NULL,lease_until=NULL WHERE task_id=? AND status='PROCESSING' AND claim_token=?",
            safeError(error), taskId.toString(), claimToken.toString()) == 1;
    }

    @Override
    public boolean resetFailed(UUID taskId) {
        if (taskId == null) throw new IllegalArgumentException("taskId must not be null");
        return jdbc.update("UPDATE storage_cleanup_task SET status='NEW',attempt_count=0,available_at=CURRENT_TIMESTAMP(6),"
                + "owner=NULL,claim_token=NULL,lease_until=NULL,last_error=NULL,completed_at=NULL WHERE task_id=? AND status='FAILED'",
            taskId.toString()) == 1;
    }

    @Override
    public boolean completeBlobAndTask(long blobId, long generation, String objectKey, UUID blobToken,
                                       UUID taskId, UUID claimToken) {
        if (blobId <= 0 || generation <= 0 || objectKey == null || objectKey.isBlank() || blobToken == null || taskId == null || claimToken == null) throw new IllegalArgumentException("invalid atomic completion arguments");
        java.util.function.Supplier<Boolean> operation = () -> {
            int blob = jdbc.update("UPDATE stored_blob SET status='DELETED',cleanup_token=NULL,cleanup_lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) "
                + "WHERE id=? AND generation=? AND object_key=? AND status='DELETING' AND cleanup_token=?", blobId, generation, objectKey, blobToken.toString());
            if (blob != 1) return false;
            return jdbc.update("UPDATE storage_cleanup_task SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP(6),owner=NULL,claim_token=NULL,lease_until=NULL "
                    + "WHERE task_id=? AND task_type='BLOB_OBJECT' AND target_id=? AND target_generation=? AND object_key=? "
                    + "AND status='PROCESSING' AND claim_token=?", taskId.toString(), Long.toString(blobId), generation,
                objectKey, claimToken.toString()) == 1;
        };
        if (transactionTemplate == null) throw new IllegalStateException("cleanup atomic completion requires an explicit transaction");
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (!operation.get()) {
                // task token 失效时必须回滚 Blob 的 DELETED 更新，交给新 owner 重试。
                status.setRollbackOnly();
                throw new IllegalStateException("cleanup atomic completion fenced");
            }
            return true;
        }));
    }

    private static void requireToken(UUID taskId, UUID token) {
        if (taskId == null || token == null) throw new IllegalArgumentException("task and claim token are required");
    }
    private static String safeError(String error) {
        if (error == null) return null;
        return error.length() <= 512 ? error : error.substring(0, 512);
    }
}
