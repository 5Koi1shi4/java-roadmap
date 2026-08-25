package com.example.search.infrastructure.persistence;

import com.example.search.application.sync.SearchCoordinationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcSearchCoordinationRepository implements SearchCoordinationRepository {
    private final JdbcTemplate jdbc;

    public JdbcSearchCoordinationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void lockShared() {
        lockSharedAndReadDispatcherPaused();
    }

    @Override
    public void lockExclusive() {
        jdbc.queryForObject("SELECT id FROM search_coordination WHERE id = 1 FOR UPDATE",
                Integer.class);
    }

    @Override
    public boolean lockSharedAndReadDispatcherPaused() {
        Boolean paused = jdbc.queryForObject(
                "SELECT dispatcher_paused FROM search_coordination WHERE id = 1 FOR SHARE",
                Boolean.class);
        return Boolean.TRUE.equals(paused);
    }

    @Override
    public void setDispatcherPaused(boolean paused) {
        if (jdbc.update("UPDATE search_coordination SET dispatcher_paused=? WHERE id=1", paused) != 1) {
            throw new IllegalStateException("coordination row is missing");
        }
    }

    @Override
    public Optional<UUID> activeRebuildId() {
        String value = jdbc.queryForObject("SELECT active_rebuild_id FROM search_coordination WHERE id = 1", String.class);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(UUID.fromString(value));
    }

    @Override
    public void setActiveRebuildId(UUID jobId) {
        if (jobId == null) throw new IllegalArgumentException("jobId is required");
        if (jdbc.update("UPDATE search_coordination SET active_rebuild_id=? WHERE id=1", jobId.toString()) != 1) {
            throw new IllegalStateException("coordination row is missing");
        }
    }

    @Override
    public boolean clearActiveRebuildId(UUID jobId) {
        if (jobId == null) throw new IllegalArgumentException("jobId is required");
        return jdbc.update("UPDATE search_coordination SET active_rebuild_id=NULL WHERE id=1 AND active_rebuild_id=?",
                jobId.toString()) == 1;
    }

    @Override
    public boolean clearActiveRebuildId(UUID jobId, String owner) {
        if (jobId == null || owner == null || owner.isBlank()) throw new IllegalArgumentException("job and owner are required");
        return jdbc.update("UPDATE search_coordination c JOIN search_rebuild_job j ON j.job_id=c.active_rebuild_id "
                        + "SET c.active_rebuild_id=NULL WHERE c.id=1 AND c.active_rebuild_id=? AND j.owner=?",
                jobId.toString(), owner) == 1;
    }
}
