package com.example.security.application;

public final class RoleNotFoundException extends RuntimeException {

    public RoleNotFoundException(long roleId) {
        super("Role not found: " + roleId);
    }
}
