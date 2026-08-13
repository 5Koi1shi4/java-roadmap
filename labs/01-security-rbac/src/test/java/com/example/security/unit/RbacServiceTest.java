package com.example.security.unit;

import com.example.security.application.RbacService;
import com.example.security.domain.Permission;
import com.example.security.domain.RbacRepository;
import com.example.security.domain.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RbacServiceTest {

    private final InMemoryRbacRepository repository = new InMemoryRbacRepository();
    private RbacService service;

    @BeforeEach
    void setUp() {
        repository.clear();
        service = new RbacService(repository);
    }

    @Test
    void grantsRoleAndReturnsItsPermissions() {
        repository.saveRole(new Role(10L, "USER_ADMIN", "用户管理员"));
        repository.savePermission(new Permission(20L, "system:user:read", "读取用户"));
        repository.linkPermission(10L, 20L);

        service.grantRole(1L, 10L);

        assertThat(service.authoritiesOf(1L)).containsExactly("system:user:read");
    }

    @Test
    void aggregatesPermissionsAcrossRolesWithoutDuplicates() {
        repository.saveRole(new Role(10L, "USER_ADMIN", "用户管理员"));
        repository.saveRole(new Role(11L, "AUDITOR", "审计员"));
        repository.savePermission(new Permission(20L, "system:user:read", "读取用户"));
        repository.linkPermission(10L, 20L);
        repository.linkPermission(11L, 20L);

        service.grantRole(1L, 10L);
        service.grantRole(1L, 11L);

        assertThat(service.authoritiesOf(1L)).containsExactly("system:user:read");
    }

    @Test
    void grantingTheSameRoleTwiceIsIdempotent() {
        repository.saveRole(new Role(10L, "USER_ADMIN", "用户管理员"));

        service.grantRole(1L, 10L);
        service.grantRole(1L, 10L);

        assertThat(repository.roleIdsOf(1L)).containsExactly(10L);
    }

    private static final class InMemoryRbacRepository implements RbacRepository {
        private final Map<Long, Role> roles = new HashMap<>();
        private final Map<Long, Permission> permissions = new HashMap<>();
        private final Map<Long, Set<Long>> userRoles = new HashMap<>();
        private final Map<Long, Set<Long>> rolePermissions = new HashMap<>();

        @Override
        public boolean roleExists(long roleId) {
            return roles.containsKey(roleId);
        }

        @Override
        public void grantRole(long userId, long roleId) {
            userRoles.computeIfAbsent(userId, ignored -> new HashSet<>()).add(roleId);
        }

        @Override
        public Set<String> findPermissionCodesByUserId(long userId) {
            Set<String> result = new HashSet<>();
            for (long roleId : roleIdsOf(userId)) {
                for (long permissionId : rolePermissions.getOrDefault(roleId, Set.of())) {
                    result.add(permissions.get(permissionId).code());
                }
            }
            return result;
        }

        void saveRole(Role role) {
            roles.put(role.id(), role);
        }

        void savePermission(Permission permission) {
            permissions.put(permission.id(), permission);
        }

        void linkPermission(long roleId, long permissionId) {
            rolePermissions.computeIfAbsent(roleId, ignored -> new HashSet<>()).add(permissionId);
        }

        Set<Long> roleIdsOf(long userId) {
            return userRoles.getOrDefault(userId, Set.of());
        }

        void clear() {
            roles.clear();
            permissions.clear();
            userRoles.clear();
            rolePermissions.clear();
        }
    }
}
