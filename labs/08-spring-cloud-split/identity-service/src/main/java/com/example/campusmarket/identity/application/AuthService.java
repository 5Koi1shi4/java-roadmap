package com.example.campusmarket.identity.application;

import com.example.campusmarket.identity.domain.CampusEmail;
import com.example.campusmarket.identity.security.IdentityTokenIssuer;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/** 注册与登录用例；公开登录只产生 ROLE_USER。 */
@Service
@Profile("!test")
public class AuthService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final IdentityTokenIssuer tokenIssuer;
    private final EmailVerificationService verificationService;

    public AuthService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, IdentityTokenIssuer tokenIssuer,
                       EmailVerificationService verificationService) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "JDBC 客户端不能为空");
        this.passwordEncoder = java.util.Objects.requireNonNull(passwordEncoder, "密码编码器不能为空");
        this.tokenIssuer = java.util.Objects.requireNonNull(tokenIssuer, "令牌签发器不能为空");
        this.verificationService = java.util.Objects.requireNonNull(verificationService, "验证码服务不能为空");
    }

    public void register(String rawEmail, String password, String code) {
        register(rawEmail, password, code, "unknown-ip", "unknown-device");
    }

    public void register(String rawEmail, String password, String code, String remoteIp, String deviceId) {
        CampusEmail email = CampusEmail.parse(rawEmail, verificationService.allowedDomains());
        validatePassword(password);
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
        return new LoginResult(user.id(), tokenIssuer.issue(principal), tokenIssuer.ttl(), principal.roles());
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new IllegalArgumentException("密码长度必须在 8 到 128 个字符之间");
        }
    }

    public record LoginResult(UUID userId, String accessToken, Duration expiresIn, Set<String> roles) {
        public LoginResult {
            java.util.Objects.requireNonNull(userId, "用户 ID 不能为空");
            java.util.Objects.requireNonNull(accessToken, "访问令牌不能为空");
            java.util.Objects.requireNonNull(expiresIn, "令牌有效期不能为空");
            roles = Set.copyOf(java.util.Objects.requireNonNull(roles, "角色不能为空"));
        }

        @Override
        public String toString() {
            return "LoginResult{redacted=true}";
        }
    }

    private record UserRow(UUID id, String passwordHash, String status) {
    }

    public static final class InvalidVerificationCodeException extends RuntimeException {
        public InvalidVerificationCodeException() {
            super("验证码无效或已过期");
        }
    }

    public static final class DuplicateEmailException extends RuntimeException {
        public DuplicateEmailException() {
            super("邮箱已注册");
        }
    }

    public static final class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() {
            super("邮箱或密码错误");
        }
    }
}
