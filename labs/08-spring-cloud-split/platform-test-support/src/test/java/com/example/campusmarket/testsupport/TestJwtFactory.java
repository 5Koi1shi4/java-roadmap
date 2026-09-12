package com.example.campusmarket.testsupport;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 使用 Nimbus JOSE 生成 RS256 测试 JWT，并提供对应的公开 JWK。
 *
 * <p>字段与统一身份契约保持一致：{@code kid}、{@code iss}、单一 {@code aud}、UUID {@code sub}、
 * 白名单 {@code roles}、{@code iat} 和固定 15 分钟的 {@code exp}。
 */
public final class TestJwtFactory {

    /** 访问令牌固定有效期。 */
    public static final Duration ACCESS_TTL = Duration.ofMinutes(15);

    private static final Set<String> ALLOWED_ROLES = Set.of("ROLE_USER", "ROLE_ADMIN");

    private TestJwtFactory() {
    }

    /**
     * 签发一个 RS256 测试 JWT。
     *
     * @param userId 令牌主体
     * @param roles 非空角色白名单子集
     * @param issuedAt 签发时间
     * @param ttl 必须恰好为 15 分钟
     * @param issuer 签发者
     * @param audience 受众
     * @param kid 密钥标识，不得为空白
     * @return 序列化后的紧凑 JWS
     */
    public static String issue(UUID userId, Set<String> roles, Instant issuedAt,
                               Duration ttl, String issuer, String audience, String kid) {
        if (!Duration.ofMinutes(15).equals(ttl) || roles == null || roles.isEmpty()
                || !Set.of("ROLE_USER", "ROLE_ADMIN").containsAll(roles)) {
            throw new IllegalArgumentException("测试 JWT 契约无效");
        }
        requireText(issuer, "issuer");
        requireText(audience, "audience");
        requireText(kid, "kid");
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(Objects.requireNonNull(userId).toString())
                .issuer(Objects.requireNonNull(issuer))
                .audience(Objects.requireNonNull(audience))
                .issueTime(Date.from(Objects.requireNonNull(issuedAt)))
                .expirationTime(Date.from(issuedAt.plus(ttl)))
                .claim("roles", roles.stream().sorted().toList())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(kid).type(JOSEObjectType.JWT).build(), claims);
        try {
            jwt.sign(new RSASSASigner(TestRsaKeys.privateKey()));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException("测试 JWT 签发失败", ex);
        }
    }

    /**
     * 返回只包含公钥材料的 RSA JWK。
     *
     * @param kid 密钥标识，不得为空白
     */
    public static RSAKey publicJwk(String kid) {
        requireText(kid, "kid");
        return new RSAKey.Builder(TestRsaKeys.publicKey())
                .keyID(kid)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 不得为空白");
        }
    }

}
