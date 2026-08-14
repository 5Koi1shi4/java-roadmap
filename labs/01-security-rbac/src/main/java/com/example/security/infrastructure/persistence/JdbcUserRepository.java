package com.example.security.infrastructure.persistence;

import com.example.security.domain.User;
import com.example.security.domain.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcUserRepository implements UserRepository {

    private static final RowMapper<User> USER_ROW_MAPPER = (resultSet, rowNumber) -> new User(
            resultSet.getLong("id"),
            resultSet.getString("username"),
            resultSet.getString("password_hash"),
            resultSet.getBoolean("enabled")
    );

    private final JdbcTemplate jdbc;

    public JdbcUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return jdbc.query("""
                        SELECT id, username, password_hash, enabled
                        FROM sys_user
                        WHERE username = ?
                        """, USER_ROW_MAPPER, username)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<User> findById(long id) {
        return jdbc.query("""
                        SELECT id, username, password_hash, enabled
                        FROM sys_user
                        WHERE id = ?
                        """, USER_ROW_MAPPER, id)
                .stream()
                .findFirst();
    }

    @Override
    public List<User> findAll() {
        return jdbc.query("""
                        SELECT id, username, password_hash, enabled
                        FROM sys_user
                        ORDER BY id
                        """, USER_ROW_MAPPER);
    }
}
