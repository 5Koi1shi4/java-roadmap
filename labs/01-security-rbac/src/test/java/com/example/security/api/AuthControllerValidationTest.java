package com.example.security.api;

import com.example.security.application.InvalidCredentialsException;
import com.example.security.application.InvalidTokenException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthControllerValidationTest {

    private final AuthController controller = new AuthController(null, null, null, null);

    @Test
    void rejectsBlankLoginCredentialsBeforeCallingServices() {
        assertThatThrownBy(() -> controller.login(new AuthController.Credentials("admin", "  ")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void rejectsMissingRefreshTokenBeforeCallingServices() {
        assertThatThrownBy(() -> controller.refresh(new AuthController.RefreshRequest(null)))
                .isInstanceOf(InvalidTokenException.class);
    }
}
