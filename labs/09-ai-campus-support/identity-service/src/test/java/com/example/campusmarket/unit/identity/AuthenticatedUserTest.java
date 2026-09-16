package com.example.campusmarket.unit.identity;

import com.example.campusmarket.identity.application.AuthenticatedUser;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedUserTest {
    @Test
    void rejectsNullEmptyAndUnknownRoles() {
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> new AuthenticatedUser(userId, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuthenticatedUser(userId, Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthenticatedUser(userId, Set.of("ROLE_HACK")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
