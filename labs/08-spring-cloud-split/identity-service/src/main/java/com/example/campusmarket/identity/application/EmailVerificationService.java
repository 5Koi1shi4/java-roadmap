package com.example.campusmarket.identity.application;

import com.example.campusmarket.identity.domain.CampusEmail;
import com.example.campusmarket.identity.infrastructure.RedisVerificationCodeStore;
import com.example.campusmarket.identity.observability.IdentityMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 验证码签发、限流和一次消费流程。 */
@Service
@Profile("!test")
public class EmailVerificationService {
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Set<String> PURPOSES = Set.of("REGISTER", "LOGIN", "CHANGE_EMAIL");

    private final JdbcTemplate jdbc;
    private final RedisVerificationCodeStore store;
    private final VerificationMailSender mailSender;
    private final Set<String> allowedDomains;
    private final IdentityMetrics metrics;

    public EmailVerificationService(JdbcTemplate jdbc, RedisVerificationCodeStore store,
                                     VerificationMailSender mailSender,
                                     @Value("${campus.market.identity.allowed-domains}") Set<String> allowedDomains) {
        this(jdbc, store, mailSender, allowedDomains, null);
    }

    @Autowired
    public EmailVerificationService(JdbcTemplate jdbc, RedisVerificationCodeStore store,
                                    VerificationMailSender mailSender,
                                    @Value("${campus.market.identity.allowed-domains}") Set<String> allowedDomains,
                                    IdentityMetrics metrics) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC 客户端不能为空");
        this.store = Objects.requireNonNull(store, "验证码存储不能为空");
        this.mailSender = Objects.requireNonNull(mailSender, "验证码邮件发送器不能为空");
        this.allowedDomains = Objects.requireNonNull(allowedDomains, "允许的邮箱域不能为空").stream()
            .filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (this.allowedDomains.isEmpty()) {
            throw new IllegalArgumentException("允许的邮箱域不能为空");
        }
        this.metrics = metrics;
    }

    public String issue(String rawEmail, String client) {
        return issue(rawEmail, "unknown-ip", "unknown-device", "REGISTER");
    }

    public String issue(String rawEmail, String client, String purpose) {
        return issue(rawEmail, "unknown-ip", "unknown-device", purpose);
    }

    public String issue(String rawEmail, String remoteIp, String deviceId, String purpose) {
        try {
            CampusEmail email = CampusEmail.parse(rawEmail, allowedDomains);
            String normalizedPurpose = normalizePurpose(purpose);
            RedisVerificationCodeStore.IssuedCode issued = store.issue(email.value(), normalizedPurpose, remoteIp, deviceId);
            Instant now = Instant.now();
            jdbc.update("INSERT INTO email_verification "
                    + "(id, user_id, email, purpose, code_hmac, status, attempt_count, expires_at, created_at) "
                    + "VALUES (?, NULL, ?, ?, ?, 'PENDING', 0, ?, ?)",
                UUID.randomUUID().toString(), email.value(), normalizedPurpose,
                issued.hmac().getBytes(StandardCharsets.UTF_8),
                Timestamp.from(now.plus(TTL)), Timestamp.from(now));
            mailSender.send(email, issued.code());
            record("SENT");
            return issued.code();
        } catch (RedisVerificationCodeStore.TooManyVerificationRequestsException limited) {
            record("RATE_LIMITED");
            throw limited;
        } catch (RuntimeException failure) {
            record("FAILURE");
            throw failure;
        }
    }

    public boolean verify(String rawEmail, String code) {
        return verify(rawEmail, code, "unknown-ip", "unknown-device", "REGISTER");
    }

    public boolean verify(String rawEmail, String code, String purpose) {
        return verify(rawEmail, code, "unknown-ip", "unknown-device", purpose);
    }

    public boolean verify(String rawEmail, String code, String remoteIp, String deviceId, String purpose) {
        try {
            return verifyInternal(rawEmail, code, remoteIp, deviceId, purpose);
        } catch (RuntimeException failure) {
            record("FAILURE");
            throw failure;
        }
    }

    public Set<String> allowedDomains() {
        return allowedDomains;
    }

    private boolean verifyInternal(String rawEmail, String code, String remoteIp, String deviceId, String purpose) {
        CampusEmail email = CampusEmail.parse(rawEmail, allowedDomains);
        String normalizedPurpose = normalizePurpose(purpose);
        if (!store.reserveFailureAttempt(email.value(), normalizedPurpose, remoteIp, deviceId)) {
            record("RATE_LIMITED");
            jdbc.update("UPDATE email_verification SET status = 'LOCKED' "
                    + "WHERE email = ? AND purpose = ? AND status = 'PENDING' "
                    + "ORDER BY created_at DESC LIMIT 1", email.value(), normalizedPurpose);
            return false;
        }
        boolean consumed = store.consume(email.value(), normalizedPurpose, code);
        if (!consumed) {
            jdbc.update("UPDATE email_verification SET "
                    + "status = CASE WHEN attempt_count >= 4 THEN 'LOCKED' ELSE status END, "
                    + "attempt_count = attempt_count + 1 "
                    + "WHERE email = ? AND purpose = ? AND status = 'PENDING' "
                    + "ORDER BY created_at DESC LIMIT 1", email.value(), normalizedPurpose);
            record("FAILURE");
            return false;
        }
        int updated = jdbc.update("UPDATE email_verification SET status = 'VERIFIED', consumed_at = ? "
                + "WHERE email = ? AND purpose = ? AND status = 'PENDING' "
                + "ORDER BY created_at DESC LIMIT 1", Timestamp.from(Instant.now()), email.value(), normalizedPurpose);
        if (updated == 1) {
            record("VERIFIED");
        } else {
            record("FAILURE");
        }
        return updated == 1;
    }

    private void record(String result) {
        if (metrics != null) {
            metrics.recordVerification(result);
        }
    }

    public static String normalizePurpose(String purpose) {
        String value = purpose == null || purpose.isBlank() ? "REGISTER" : purpose.trim().toUpperCase(java.util.Locale.ROOT);
        if (!PURPOSES.contains(value)) {
            throw new IllegalArgumentException("验证码用途不受支持");
        }
        return value;
    }
}
