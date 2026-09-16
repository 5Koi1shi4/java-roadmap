package com.example.campusmarket.security;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 从受信 JWT 映射出的最小用户主体。 */
public record AuthenticatedUser(UUID userId, Set<String> roles) {
    public AuthenticatedUser {
        Objects.requireNonNull(userId, "用户 ID 不能为空");
        roles = Set.copyOf(roles);
    }
}
