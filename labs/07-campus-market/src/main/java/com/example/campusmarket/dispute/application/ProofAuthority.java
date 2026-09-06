package com.example.campusmarket.dispute.application;

import java.util.Objects;
import java.util.UUID;

/** 可信退回证明的受控签发者；调用方不能仅凭字符串自报证明来源。 */
public record ProofAuthority(Kind kind, UUID actorId, String attestationReference) {
    public enum Kind { SELLER, ADMIN, PROVIDER }

    public ProofAuthority {
        Objects.requireNonNull(kind, "证明授权类型不能为空");
        if (kind == Kind.PROVIDER) {
            if (attestationReference == null || attestationReference.isBlank())
                throw new IllegalArgumentException("物流验签事实引用不能为空");
            actorId = null;
        } else if (actorId == null) {
            throw new IllegalArgumentException("用户授权主体不能为空");
        } else if (attestationReference != null) {
            throw new IllegalArgumentException("用户授权不得携带物流引用");
        }
    }

    public static ProofAuthority seller(UUID sellerId) { return new ProofAuthority(Kind.SELLER, sellerId, null); }
    public static ProofAuthority admin(UUID adminId) { return new ProofAuthority(Kind.ADMIN, adminId, null); }
    public static ProofAuthority provider(String verifiedReference) { return new ProofAuthority(Kind.PROVIDER, null, verifiedReference); }
}
