package com.example.files.domain;

import java.text.Normalizer;
import java.util.Locale;

/** A filename suitable for display and header construction, never for object paths. */
public record SafeDisplayName(String value) {

    public SafeDisplayName {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("display name must not be blank");
        }
        if (value.length() > 255) {
            throw new IllegalArgumentException("display name is too long");
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
            || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
            || value.chars().anyMatch(c -> Character.isISOControl((char) c))) {
            throw new IllegalArgumentException("display name contains unsafe characters");
        }
    }

    public static SafeDisplayName from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("display name must not be blank");
        }

        StringBuilder cleaned = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '/' || c == '\\') {
                continue;
            }
            if (c == '\r' || c == '\n' || Character.isISOControl(c) || Character.isWhitespace(c)) {
                cleaned.append(' ');
            } else if (c == ':' || c == '<' || c == '>' || c == '"' || c == '|'
                || c == '?' || c == '*') {
                cleaned.append('_');
            } else {
                cleaned.append(c);
            }
        }

        String value = cleaned.toString().replaceAll("\\s+", " ").trim();
        while (!value.isEmpty() && (value.charAt(0) == '.' || value.charAt(0) == ' ')) {
            value = value.substring(1);
        }
        while (!value.isEmpty() && (value.endsWith(".") || value.endsWith(" "))) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException("display name must not be blank");
        }
        if (value.length() > 255) {
            throw new IllegalArgumentException("display name is too long");
        }

        String stem = value;
        int dot = value.indexOf('.');
        if (dot >= 0) {
            stem = value.substring(0, dot);
        }
        if (isWindowsReservedName(stem)) {
            value = value + "_";
        }
        return new SafeDisplayName(value);
    }

    /** An ASCII-only fallback for Content-Disposition's legacy filename parameter. */
    public String asciiFallback() {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replaceAll("[^\\p{ASCII}]", "");
        String fallback = normalized.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        return fallback.isEmpty() ? "download" : fallback;
    }

    private static boolean isWindowsReservedName(String stem) {
        String upper = stem.toUpperCase(Locale.ROOT);
        return upper.equals("CON") || upper.equals("PRN") || upper.equals("AUX") || upper.equals("NUL")
            || (upper.length() == 4 && (upper.startsWith("COM") || upper.startsWith("LPT"))
            && upper.charAt(3) >= '1' && upper.charAt(3) <= '9');
    }
}
