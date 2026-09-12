package com.example.campusmarket.identity.security;

import com.example.campusmarket.identity.application.AuthenticatedUser;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** RS256 access-token 签发与验证门面。 */
@Service
public final class IdentityTokenIssuer {
    public static final Duration ACCESS_TTL = Duration.ofMinutes(15);

    private final RsaKeyProperties properties;
    private final java.security.interfaces.RSAPrivateKey privateKey;
    private final java.security.interfaces.RSAPublicKey publicKey;
    private final Clock clock;

    @Autowired
    public IdentityTokenIssuer(RsaKeyProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public IdentityTokenIssuer(RsaKeyProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "JWT properties are required");
        this.clock = Objects.requireNonNull(clock, "JWT clock is required");
        this.privateKey = RsaKeyProperties.readPrivateKey(properties.privateKey());
        this.publicKey = RsaKeyProperties.readPublicKey(properties.publicKey());
        RsaKeyProperties.validateKeyPair(privateKey, publicKey);
    }

    /** 签发固定 15 分钟、带完整身份契约的 RS256 JWT。 */
    public String issue(AuthenticatedUser user) {
        Objects.requireNonNull(user, "authenticated user is required");
        Instant issuedAt = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(properties.issuer())
            .audience(properties.audience())
            .subject(user.userId().toString())
            .claim("roles", user.roles().stream().sorted().toList())
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(issuedAt.plus(ACCESS_TTL)))
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
            .keyID(properties.keyId()).type(JOSEObjectType.JWT).build(), claims);
        try {
            jwt.sign(new RSASSASigner(privateKey));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException("JWT signing failed", ex);
        }
    }

    /** 解析并严格校验签名、issuer、audience、kid、时间和角色主张。 */
    public JWTClaimsSet parse(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("JWT is required");
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())
                || !Objects.equals(properties.keyId(), jwt.getHeader().getKeyID())) {
                throw new IllegalArgumentException("JWT algorithm or key id is invalid");
            }
            if (!jwt.verify(new RSASSAVerifier(publicKey))) {
                throw new IllegalArgumentException("JWT signature is invalid");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            validateClaims(claims);
            return claims;
        } catch (ParseException | JOSEException | ClassCastException ex) {
            throw new IllegalArgumentException("JWT is invalid", ex);
        }
    }

    /** 将严格校验后的 JWT 映射为认证主体。 */
    public AuthenticatedUser authenticate(String token) {
        JWTClaimsSet claims = parse(token);
        try {
            UUID userId = UUID.fromString(claims.getSubject());
            if (!userId.toString().equalsIgnoreCase(claims.getSubject())) {
                throw new IllegalArgumentException("JWT subject is not a canonical UUID");
            }
            List<String> roles = claims.getStringListClaim("roles");
            return new AuthenticatedUser(userId, Set.copyOf(roles));
        } catch (ParseException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("JWT claims are invalid", ex);
        }
    }

    /** 仅返回可公开发布的 JWK，不携带任何私钥参数。 */
    public RSAKey publicJwk() {
        return new RSAKey.Builder(publicKey)
            .keyID(properties.keyId())
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .build()
            .toPublicJWK();
    }

    public Duration ttl() {
        return ACCESS_TTL;
    }

    public String issuer() {
        return properties.issuer();
    }

    public String audience() {
        return properties.audience();
    }

    public String keyId() {
        return properties.keyId();
    }

    public java.security.interfaces.RSAPublicKey publicKey() {
        return publicKey;
    }

    private void validateClaims(JWTClaimsSet claims) {
        if (!Objects.equals(properties.issuer(), claims.getIssuer())
            || !List.of(properties.audience()).equals(claims.getAudience())
            || claims.getSubject() == null || claims.getSubject().isBlank()
            || claims.getIssueTime() == null || claims.getExpirationTime() == null) {
            throw new IllegalArgumentException("JWT required claims are invalid");
        }
        try {
            UUID subject = UUID.fromString(claims.getSubject());
            if (!subject.toString().equalsIgnoreCase(claims.getSubject())) {
                throw new IllegalArgumentException("JWT subject is invalid");
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("JWT subject is invalid", ex);
        }
        Object rawRoles = claims.getClaim("roles");
        if (!(rawRoles instanceof List<?> roleList) || roleList.isEmpty()
            || roleList.stream().anyMatch(role -> !(role instanceof String)
                || ((String) role).isBlank() || !AuthenticatedUser.ALLOWED_ROLES.contains(role))) {
            throw new IllegalArgumentException("JWT roles are invalid");
        }
        Instant issuedAt = claims.getIssueTime().toInstant();
        Instant expiresAt = claims.getExpirationTime().toInstant();
        if (issuedAt.isAfter(clock.instant())
            || !ACCESS_TTL.equals(Duration.between(issuedAt, expiresAt))
            || !expiresAt.isAfter(clock.instant())) {
            throw new IllegalArgumentException("JWT lifetime is invalid");
        }
    }
}
