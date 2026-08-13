package com.example.security.domain;

import java.util.Set;

public interface RbacRepository {

    boolean roleExists(long roleId);

    void grantRole(long userId, long roleId);

    Set<String> findPermissionCodesByUserId(long userId);
}
