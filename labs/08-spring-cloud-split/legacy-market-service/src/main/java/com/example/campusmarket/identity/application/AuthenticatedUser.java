package com.example.campusmarket.identity.application;

import java.util.Set;
import java.util.UUID;

public record AuthenticatedUser(UUID userId, Set<String> roles) {
    public AuthenticatedUser {
        roles = Set.copyOf(roles);
    }
}
