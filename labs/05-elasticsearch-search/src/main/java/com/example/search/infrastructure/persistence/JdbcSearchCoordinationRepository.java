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
}
