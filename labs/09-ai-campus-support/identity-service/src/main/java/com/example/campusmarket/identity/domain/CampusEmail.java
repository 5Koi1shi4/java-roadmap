package com.example.campusmarket.identity.domain;

import java.net.IDN;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 受允许域约束且已规范化的校园邮箱。 */
public record CampusEmail(String value, String localPart, String domain) {
    public CampusEmail {
        requireText(value, "邮箱");
        requireText(localPart, "邮箱本地部分");
        requireText(domain, "邮箱域");
    }

    public static CampusEmail parse(String raw, Set<String> allowedDomains) {
        if (raw == null || allowedDomains == null || allowedDomains.isEmpty()) {
            throw new IllegalArgumentException("邮箱和允许域不能为空");
        }
        if (!raw.equals(raw.trim())) {
            throw new IllegalArgumentException("校园邮箱格式无效");
        }
        int at = raw.lastIndexOf('@');
        if (at <= 0 || at == raw.length() - 1 || raw.indexOf('@') != at) {
            throw new IllegalArgumentException("校园邮箱格式无效");
        }
        String local = raw.substring(0, at);
        if (local.codePoints().anyMatch(Character::isWhitespace)
            || local.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("校园邮箱格式无效");
        }
        String domain = normalizeDomain(raw.substring(at + 1));
        boolean allowed = allowedDomains.stream().filter(Objects::nonNull)
            .map(CampusEmail::normalizeDomain).anyMatch(domain::equals);
        if (!allowed) {
            throw new IllegalArgumentException("邮箱域不在允许范围内");
        }
        return new CampusEmail(local + "@" + domain, local, domain);
    }

    private static String normalizeDomain(String rawDomain) {
        if (rawDomain == null || rawDomain.isBlank() || rawDomain.contains("@")
            || rawDomain.codePoints().anyMatch(Character::isWhitespace)
            || rawDomain.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("邮箱域格式无效");
        }
        try {
            return IDN.toASCII(rawDomain, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("邮箱域格式无效", ex);
        }
    }

    private static void requireText(String value, String label) {
        Objects.requireNonNull(value, label + "不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空白");
        }
    }
}
