package com.example.security.infrastructure.persistence;

import com.example.security.domain.RefreshTokenSession;
import com.example.security.domain.RefreshTokenSessionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class JdbcRefreshTokenSessionRepository implements RefreshTokenSessionRepository {

    private final JdbcTemplate jdbc;

    public JdbcRefreshTokenSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(RefreshTokenSession session) {
        jdbc.update("""
                INSERT INTO refresh_token (token_hash, user_id, expires_at, revoked)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE expires_at = VALUES(expires_at), revoked = VALUES(revoked)
                """,
                session.tokenHash(), session.userId(), Timestamp.from(session.expiresAt()), session.revoked());
    }

    @Override
    public Optional<RefreshTokenSession> findByHash(String tokenHash) {
        return jdbc.query("""
                        SELECT token_hash, user_id, expires_at, revoked
                        FROM refresh_token
                        WHERE token_hash = ?
                        """, (resultSet, rowNumber) -> new RefreshTokenSession(
                        resultSet.getString("token_hash"),
                        resultSet.getLong("user_id"),
                        resultSet.getTimestamp("expires_at").toInstant(),
                        resultSet.getBoolean("revoked")
                ), tokenHash)
                .stream()
                .findFirst();
    }

    @Override
    public boolean revokeIfUsable(String tokenHash, Instant now) {
        return jdbc.update("""
                UPDATE refresh_token
                SET revoked = TRUE
                WHERE token_hash = ?
                  AND revoked = FALSE
                  AND expires_at > ?
                """, tokenHash, Timestamp.from(now)) == 1;
    }
}
