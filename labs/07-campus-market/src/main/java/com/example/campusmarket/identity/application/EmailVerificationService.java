package com.example.campusmarket.identity.application;

import com.example.campusmarket.identity.domain.CampusEmail;
import com.example.campusmarket.identity.infrastructure.RedisVerificationCodeStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
@Profile("!test")
public class EmailVerificationService {
    private final JdbcTemplate jdbc;
    private final RedisVerificationCodeStore store;
    private final Set<String> allowedDomains;
    private final Duration ttl;

    public EmailVerificationService(JdbcTemplate jdbc, RedisVerificationCodeStore store,
                                    @Value("${campus.market.identity.allowed-domains}") Set<String> allowedDomains,
                                    @Value("${campus.market.identity.verification-ttl:10m}") Duration ttl) {
        this.jdbc = jdbc;
        this.store = store;
        this.allowedDomains = allowedDomains;
        this.ttl = ttl;
    }

    public String issue(String rawEmail, String client) {
        return issue(rawEmail, client, "REGISTER");
    }

    public String issue(String rawEmail, String client, String purpose) {
        CampusEmail email = CampusEmail.parse(rawEmail, allowedDomains);
        String normalizedPurpose = normalizePurpose(purpose);
        RedisVerificationCodeStore.IssuedCode issued = store.issue(email.value(), normalizedPurpose, client);
        Instant now = Instant.now();
        jdbc.update("INSERT INTO email_verification "
                + "(id, user_id, email, purpose, code_hmac, status, attempt_count, expires_at, created_at) "
                + "VALUES (?, NULL, ?, ?, ?, 'PENDING', 0, ?, ?)",
            UUID.randomUUID().toString(), email.value(), normalizedPurpose,
            issued.hmac().getBytes(java.nio.charset.StandardCharsets.UTF_8),
            Timestamp.from(now.plus(ttl)), Timestamp.from(now));
        // The code is returned to the local/test simulated mail adapter only.
        return issued.code();
    }

    public boolean verify(String rawEmail, String code) {
        return verify(rawEmail, code, "REGISTER");
    }

    public boolean verify(String rawEmail, String code, String purpose) {
        CampusEmail email = CampusEmail.parse(rawEmail, allowedDomains);
        String normalizedPurpose = normalizePurpose(purpose);
        boolean consumed = store.consume(email.value(), normalizedPurpose, code);
        if (!consumed) {
            jdbc.update("UPDATE email_verification SET "
                    + "status = CASE WHEN attempt_count >= 4 THEN 'LOCKED' ELSE status END, "
                    + "attempt_count = attempt_count + 1 "
                    + "WHERE email = ? AND purpose = ? AND status = 'PENDING' "
                    + "ORDER BY created_at DESC LIMIT 1", email.value(), normalizedPurpose);
            return false;
        }
        int updated = jdbc.update("UPDATE email_verification SET status = 'VERIFIED', consumed_at = ? "
                + "WHERE email = ? AND purpose = ? AND status = 'PENDING' "
                + "ORDER BY created_at DESC LIMIT 1", Timestamp.from(Instant.now()), email.value(), normalizedPurpose);
        return updated == 1;
    }

    public Set<String> allowedDomains() {
        return allowedDomains;
    }

    private static String normalizePurpose(String purpose) {
        String value = purpose == null || purpose.isBlank() ? "REGISTER" : purpose.trim().toUpperCase();
        if (!Set.of("REGISTER", "LOGIN", "CHANGE_EMAIL").contains(value)) {
            throw new IllegalArgumentException("Unsupported verification purpose");
        }
        return value;
    }
}
