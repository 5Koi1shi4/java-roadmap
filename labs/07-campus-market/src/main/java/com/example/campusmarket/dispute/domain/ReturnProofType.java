package com.example.campusmarket.dispute.domain;

import java.util.Locale;

/** 退回证明来源；只有平台可验证的三类证明可以触发自动退款。 */
public enum ReturnProofType {
    BUYER_EVIDENCE(false),
    SELLER_CONFIRMED(true),
    PROVIDER_DELIVERED(true),
    ADMIN_CONFIRMED(true);

    private final boolean trusted;

    ReturnProofType(boolean trusted) { this.trusted = trusted; }

    public boolean isTrusted() { return trusted; }

    public static ReturnProofType parse(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("证明类型不能为空");
        try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("证明类型无效", ex); }
    }
}
