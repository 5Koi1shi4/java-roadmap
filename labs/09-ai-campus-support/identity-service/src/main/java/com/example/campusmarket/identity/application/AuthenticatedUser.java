package com.example.campusmarket.identity.application;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 身份领域公开给令牌签发器的最小用户主张。 */
public record AuthenticatedUser(UUID userId, Set<String> roles) {
    public static final Set<String> ALLOWED_ROLES = Set.of("ROLE_USER", "ROLE_ADMIN");

    public AuthenticatedUser {
        Objects.requireNonNull(userId, "用户 ID 不能为空");
        Objects.requireNonNull(roles, "角色不能为空");
        if (roles.isEmpty() || roles.stream().anyMatch(role -> role == null || role.isBlank())
            || !ALLOWED_ROLES.containsAll(roles)) {
            throw new IllegalArgumentException("角色必须是非空白名单子集");
        }
        roles = Set.copyOf(roles);
    }
}
