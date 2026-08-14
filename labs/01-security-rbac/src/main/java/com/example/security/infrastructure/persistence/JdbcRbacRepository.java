package com.example.security.infrastructure.persistence;

import com.example.security.domain.RbacRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashSet;
import java.util.Set;

@Repository
public class JdbcRbacRepository implements RbacRepository {

    private final JdbcTemplate jdbc;

    public JdbcRbacRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean roleExists(long roleId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sys_role WHERE id = ?", Integer.class, roleId);
        return count != null && count == 1;
    }

    @Override
    public void grantRole(long userId, long roleId) {
        jdbc.update("""
                INSERT INTO sys_user_role (user_id, role_id)
                VALUES (?, ?)
                ON DUPLICATE KEY UPDATE user_id = VALUES(user_id)
                """, userId, roleId);
    }

    @Override
    public Set<String> findPermissionCodesByUserId(long userId) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT DISTINCT permission.code
                FROM sys_permission permission
                JOIN sys_role_permission role_permission ON role_permission.permission_id = permission.id
                JOIN sys_user_role user_role ON user_role.role_id = role_permission.role_id
                WHERE user_role.user_id = ?
                ORDER BY permission.code
                """, String.class, userId));
    }
}
