package com.example.campusmarket.identity.domain;

import java.net.IDN;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** A normalized email address whose domain is explicitly allowed by the application. */
public record CampusEmail(String value, String localPart, String domain) {
    public CampusEmail {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(localPart, "localPart");
        Objects.requireNonNull(domain, "domain");
    }

    public static CampusEmail parse(String raw, Set<String> allowedDomains) {
        if (raw == null || allowedDomains == null) {
            throw new IllegalArgumentException("Email and allowed domains are required");
        }
        if (!raw.equals(raw.trim())) {
            throw new IllegalArgumentException("Invalid campus email");
        }
        String email = raw;
        int at = email.lastIndexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            throw new IllegalArgumentException("Invalid campus email");
        }
        String local = email.substring(0, at);
        if (local.codePoints().anyMatch(Character::isWhitespace)
            || local.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid campus email");
        }
        String domain = normalizeDomain(email.substring(at + 1));
        boolean allowed = allowedDomains.stream()
            .map(CampusEmail::normalizeDomain)
            .anyMatch(domain::equals);
        if (!allowed) {
            throw new IllegalArgumentException("Email domain is not allowed");
        }
        String normalized = local + "@" + domain;
        return new CampusEmail(normalized, local, domain);
    }

    private static String normalizeDomain(String rawDomain) {
        if (rawDomain == null || rawDomain.isBlank() || rawDomain.contains("@")
            || rawDomain.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Invalid email domain");
        }
        try {
            return IDN.toASCII(rawDomain, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid email domain", ex);
        }
    }
}
