package com.example.campusmarket.dispute.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/** 固定的普通争议理由；排除理由保留在案件中供人工复核。 */
public enum DisputeReason {
    QUANTITY(false), MODEL(false), APPEARANCE(false), MISSING_PARTS(false),
    NOT_AS_DESCRIBED(false), FUNCTIONAL_DEFECT(false),
    WATER_DAMAGE(true), DROP_DAMAGE(true), WRONG_POWER(true),
    UNAUTHORIZED_REPAIR(true), NORMAL_WEAR(true), DISCLOSED_ISSUE(true);

    private final boolean exclusion;

    DisputeReason(boolean exclusion) { this.exclusion = exclusion; }

    public boolean isExclusion() { return exclusion; }

    public static DisputeReason parse(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("争议理由不能为空");
        try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("争议理由无效", ex); }
    }

    public boolean allowedAt(Instant t0, Instant now) {
        if (exclusion || t0 == null || now == null || now.isBefore(t0)) return false;
        if (!now.isBefore(t0.plus(Duration.ofDays(7)))) return false;
        return now.isBefore(t0.plus(Duration.ofHours(72))) || this == FUNCTIONAL_DEFECT;
    }
}
