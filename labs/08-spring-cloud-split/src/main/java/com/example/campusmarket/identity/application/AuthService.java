package com.example.campusmarket.identity.application;

import com.example.campusmarket.identity.domain.CampusEmail;
import com.example.campusmarket.identity.infrastructure.JwtService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

import java.util.Set;
import java.util.UUID;

@Service
@Profile("!test")
public class AuthService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmailVerificationService verificationService;

    public AuthService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, JwtService jwtService,
                       EmailVerificationService verificationService) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.verificationService = verificationService;
    }

    public void register(String rawEmail, String password, String code) {
        register(rawEmail, password, code, "unknown-ip", "unknown-device");
    }

    public void register(String rawEmail, String password, String code, String remoteIp, String deviceId) {
        CampusEmail email = CampusEmail.parse(rawEmail, verificationService.allowedDomains());
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new IllegalArgumentException("Password must be between 8 and 128 characters");
        }
        if (!verificationService.verify(email.value(), code, remoteIp, deviceId, "REGISTER")) {
            throw new InvalidVerificationCodeException();
        }
        UUID userId = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO campus_user "
                    + "(id, email, password_hash, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                userId.toString(), email.value(), passwordEncoder.encode(password));
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateEmailException();
        }
    }

    public LoginResult login(String rawEmail, String password) {
        CampusEmail email = CampusEmail.parse(rawEmail, verificationService.allowedDomains());
        UserRow user = jdbc.query("SELECT id, password_hash, status FROM campus_user WHERE email = ?",
            rs -> rs.next() ? new UserRow(UUID.fromString(rs.getString("id")), rs.getString("password_hash"),
                rs.getString("status")) : null, email.value());
        if (user == null || !"ACTIVE".equals(user.status()) || password == null
            || !passwordEncoder.matches(password, user.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        AuthenticatedUser principal = new AuthenticatedUser(user.id(), Set.of("ROLE_USER"));
        return new LoginResult(user.id(), jwtService.issue(principal), jwtService.ttl(), principal.roles());
    }

    public record LoginResult(UUID userId, String accessToken, java.time.Duration expiresIn, Set<String> roles) {
    }

    private record UserRow(UUID id, String passwordHash, String status) {
    }

    public static final class InvalidVerificationCodeException extends RuntimeException {
        public InvalidVerificationCodeException() {
            super("Invalid or expired verification code");
        }
    }

    public static final class DuplicateEmailException extends RuntimeException {
        public DuplicateEmailException() {
            super("Email is already registered");
        }
    }

    public static final class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() {
            super("Invalid credentials");
        }
    }
}
