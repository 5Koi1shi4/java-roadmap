package com.example.security.domain;

import java.util.Optional;

public interface RefreshTokenSessionRepository {

    void save(RefreshTokenSession session);

    Optional<RefreshTokenSession> findByHash(String tokenHash);
}
