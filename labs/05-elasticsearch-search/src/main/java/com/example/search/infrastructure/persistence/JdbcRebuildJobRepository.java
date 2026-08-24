package com.example.search.infrastructure.persistence;

import com.example.search.application.maintenance.RebuildJob;
import com.example.search.application.maintenance.RebuildJobRepository;
import com.example.search.application.maintenance.RebuildPhase;
import com.example.search.application.maintenance.RebuildStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcRebuildJobRepository implements RebuildJobRepository {
    private final JdbcTemplate jdbc;

    public JdbcRebuildJobRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void insert(RebuildJob job) {
        jdbc.update("INSERT INTO search_rebuild_job(job_id, target_index, status, phase, owner, lease_until, "
                        + "start_watermark, final_watermark, imported_count, difference_count, last_error, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, TIMESTAMPADD(SECOND, 30, UTC_TIMESTAMP(6)), ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))",
                job.jobId().toString(), job.targetIndex(), job.status().name(), job.phase().name(), job.owner(),
                nullable(job.startWatermark()), nullable(job.finalWatermark()), job.importedCount(),
                job.differenceCount(), job.lastError());
    }

    @Override
    public Optional<RebuildJob> find(UUID jobId) {
        return query(jobId, "");
    }

    @Override
    public Optional<RebuildJob> findForUpdate(UUID jobId) {
        return query(jobId, " FOR UPDATE");
    }

    @Override
    public boolean hasValidLeaseForUpdate(UUID jobId) {
        Boolean valid = jdbc.queryForObject("SELECT (status IN ('PENDING','RUNNING') AND lease_until > UTC_TIMESTAMP(6)) "
                        + "FROM search_rebuild_job WHERE job_id=?", Boolean.class, jobId.toString());
        return Boolean.TRUE.equals(valid);
    }

    @Override
    public boolean markRunning(UUID jobId, String owner, Instant leaseUntil) {
        return jdbc.update("UPDATE search_rebuild_job SET status='RUNNING', phase='SNAPSHOT', "
                        + "lease_until=TIMESTAMPADD(SECOND, 30, UTC_TIMESTAMP(6)) "
                        + "WHERE job_id=? AND status='PENDING' AND owner=? AND lease_until > UTC_TIMESTAMP(6)",
                jobId.toString(), owner) == 1;
    }

    @Override
    public boolean setStartWatermark(UUID jobId, String owner, long watermark) {
        return jdbc.update("UPDATE search_rebuild_job SET start_watermark=?, phase='SNAPSHOT', "
                        + "lease_until=TIMESTAMPADD(SECOND, 30, UTC_TIMESTAMP(6)) "
                        + "WHERE job_id=? AND status='RUNNING' AND owner=? AND lease_until > UTC_TIMESTAMP(6)",
                watermark, jobId.toString(), owner) == 1;
    }

    @Override
    public boolean setCatchUp(UUID jobId, String owner, long preparedWatermark, long importedCount) {
        return jdbc.update("UPDATE search_rebuild_job SET final_watermark=?, phase='CATCH_UP', imported_count=?, "
                        + "lease_until=TIMESTAMPADD(SECOND, 30, UTC_TIMESTAMP(6)) "
                        + "WHERE job_id=? AND status='RUNNING' AND owner=? AND lease_until > UTC_TIMESTAMP(6)",
                preparedWatermark, importedCount, jobId.toString(), owner) == 1;
    }

    @Override
    public boolean addImportedCount(UUID jobId, String owner, long delta) {
        if (delta < 0) throw new IllegalArgumentException("delta must not be negative");
        return jdbc.update("UPDATE search_rebuild_job SET imported_count=imported_count+? "
                        + "WHERE job_id=? AND status='RUNNING' AND owner=? AND lease_until > UTC_TIMESTAMP(6)",
                delta, jobId.toString(), owner) == 1;
    }

    @Override
    public boolean renewLease(UUID jobId, String owner, Instant leaseUntil) {
        return jdbc.update("UPDATE search_rebuild_job SET lease_until=TIMESTAMPADD(SECOND, 30, UTC_TIMESTAMP(6)) "
                        + "WHERE job_id=? AND status='RUNNING' "
                        + "AND owner=? AND lease_until > UTC_TIMESTAMP(6)",
                jobId.toString(), owner) == 1;
    }

    @Override
    public boolean markFailed(UUID jobId, String owner, String reason) {
        return jdbc.update("UPDATE search_rebuild_job SET status='FAILED', last_error=?, completed_at=UTC_TIMESTAMP(6) "
                        + "WHERE job_id=? AND status IN ('PENDING','RUNNING') AND owner=?", normalize(reason), jobId.toString(), owner) == 1;
    }

    private Optional<RebuildJob> query(UUID jobId, String suffix) {
        return jdbc.query("SELECT job_id, target_index, status, phase, owner, lease_until, start_watermark, final_watermark, "
                        + "imported_count, difference_count, last_error, created_at, completed_at FROM search_rebuild_job WHERE job_id=?" + suffix,
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(), jobId.toString());
    }

    private RebuildJob map(ResultSet rs) throws SQLException {
        LocalDateTime completed = rs.getObject("completed_at", LocalDateTime.class);
        return new RebuildJob(UUID.fromString(rs.getString("job_id")), rs.getString("target_index"),
                RebuildStatus.valueOf(rs.getString("status")), RebuildPhase.valueOf(rs.getString("phase")),
                rs.getString("owner"), utcInstant(rs, "lease_until"),
                nullableLong(rs, "start_watermark"), nullableLong(rs, "final_watermark"),
                rs.getLong("imported_count"), rs.getLong("difference_count"), rs.getString("last_error"),
                utcInstant(rs, "created_at"), completed == null ? null : completed.toInstant(ZoneOffset.UTC));
    }

    private static Instant utcInstant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value.toInstant(ZoneOffset.UTC);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Object nullable(Long value) { return value; }

    private static String normalize(String reason) {
        if (reason == null) return null;
        reason = reason.replace("\r", "").replace("\n", "");
        return reason.length() <= 1024 ? reason : reason.substring(0, 1024);
    }
}
