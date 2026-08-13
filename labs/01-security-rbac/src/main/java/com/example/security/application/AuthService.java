package com.example.security.application;

import com.example.security.domain.User;
import com.example.security.domain.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Objects;

public final class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = Objects.requireNonNull(users);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
    }

    public User authenticate(String username, String rawPassword) {
        User user = users.findByUsername(username)
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(rawPassword, user.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        if (!user.enabled()) {
            throw new UserDisabledException();
        }
        return user;
    }
}
