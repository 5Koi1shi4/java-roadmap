package com.example.security.application;

import com.example.security.domain.RbacRepository;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class RbacService {

    private final RbacRepository repository;

    public RbacService(RbacRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    public void grantRole(long userId, long roleId) {
        requirePositive(userId, "userId");
        requirePositive(roleId, "roleId");
        if (!repository.roleExists(roleId)) {
            throw new RoleNotFoundException(roleId);
        }
        repository.grantRole(userId, roleId);
    }

    public Set<String> authoritiesOf(long userId) {
        requirePositive(userId, "userId");
        return Collections.unmodifiableSet(
                new TreeSet<>(repository.findPermissionCodesByUserId(userId))
        );
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
