package com.example.security.unit;

import com.example.security.application.AuthService;
import com.example.security.application.InvalidCredentialsException;
import com.example.security.application.UserDisabledException;
import com.example.security.domain.User;
import com.example.security.domain.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthServiceTest {

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final InMemoryUserRepository users = new InMemoryUserRepository();
    private AuthService authService;

    @BeforeEach
    void setUp() {
        users.clear();
        authService = new AuthService(users, passwordEncoder);
    }

    @Test
    void returnsAuthenticatedUserWhenPasswordMatches() {
        users.save(new User(1L, "student", passwordEncoder.encode("correct-password"), true));

        User authenticated = authService.authenticate("student", "correct-password");

        assertThat(authenticated.id()).isEqualTo(1L);
        assertThat(authenticated.username()).isEqualTo("student");
    }

    @Test
    void rejectsLoginWhenPasswordDoesNotMatch() {
        users.save(new User(1L, "student", passwordEncoder.encode("correct-password"), true));

        assertThatThrownBy(() -> authService.authenticate("student", "wrong-password"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void rejectsLoginWhenUserIsDisabled() {
        users.save(new User(1L, "student", passwordEncoder.encode("correct-password"), false));

        assertThatThrownBy(() -> authService.authenticate("student", "correct-password"))
                .isInstanceOf(UserDisabledException.class);
    }

    private static final class InMemoryUserRepository implements UserRepository {
        private final Map<String, User> users = new HashMap<>();

        @Override
        public Optional<User> findByUsername(String username) {
            return Optional.ofNullable(users.get(username));
        }

        @Override
        public Optional<User> findById(long id) {
            return users.values().stream()
                    .filter(user -> user.id() == id)
                    .findFirst();
        }

        @Override
        public List<User> findAll() {
            return List.copyOf(users.values());
        }

        void save(User user) {
            users.put(user.username(), user);
        }

        void clear() {
            users.clear();
        }
    }
}
