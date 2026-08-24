package com.example.search.infrastructure.persistence;

import com.example.search.application.sync.SearchCoordinationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSearchCoordinationRepository implements SearchCoordinationRepository {
    private final JdbcTemplate jdbc;

    public JdbcSearchCoordinationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void lockShared() {
        jdbc.queryForObject("SELECT id FROM search_coordination WHERE id = 1 FOR SHARE", Integer.class);
    }
}
