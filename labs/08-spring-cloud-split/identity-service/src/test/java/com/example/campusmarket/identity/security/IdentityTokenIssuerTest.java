package com.example.campusmarket.identity.security;

import com.example.campusmarket.identity.application.AuthenticatedUser;
import com.example.campusmarket.testsupport.TestRsaKeys;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityTokenIssuerTest {
    private static Path keys;

    @BeforeAll
    static void writeTestKeys() throws Exception {
        keys = Files.createTempDirectory("identity-token-keys");
        TestRsaKeys.writePemPair(keys);
    }

    @Test
    void signsExactFifteenMinuteRs256Token() throws Exception {
        IdentityTokenIssuer issuer = new IdentityTokenIssuer(properties());
        UUID userId = UUID.randomUUID();

        String token = issuer.issue(new AuthenticatedUser(userId, Set.of("ROLE_USER")));
        SignedJWT jwt = SignedJWT.parse(token);

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("identity-key-1");
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("http://gateway.test");
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("campus-market-api");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant())
            .isEqualTo(jwt.getJWTClaimsSet().getIssueTime().toInstant().plusSeconds(900));
    }

    @Test
    void rejectsIllegalAudienceAndMismatchedKeysAtConfigurationBoundary() {
        assertThatThrownBy(() -> new RsaKeyProperties("http://gateway.test", "wrong-audience",
            "identity-key-1", privateKey(), publicKey()))
            .isInstanceOf(IllegalArgumentException.class);

        Path other = assertDoesNotThrowTempDirectory();
        try {
            Files.writeString(other.resolve("bad-public-key.pem"), "not-a-key");
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
        assertThatThrownBy(() -> new RsaKeyProperties("http://gateway.test", "campus-market-api",
            "identity-key-1", privateKey(), new FileSystemResource(other.resolve("bad-public-key.pem"))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSameModulusWithWrongPublicExponent() throws Exception {
        RSAPublicKey matching = TestRsaKeys.publicKey();
        RSAPublicKey wrongExponent = (RSAPublicKey) KeyFactory.getInstance("RSA")
            .generatePublic(new RSAPublicKeySpec(matching.getModulus(), BigInteger.valueOf(3)));
        Path directory = Files.createTempDirectory("identity-wrong-exponent");
        Path publicKey = directory.resolve("wrong-public-key.pem");
        Files.writeString(publicKey, pem(wrongExponent.getEncoded(), "PUBLIC KEY"), StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> new RsaKeyProperties("http://gateway.test", "campus-market-api",
            "identity-key-1", privateKey(), new FileSystemResource(publicKey)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTokenIssuedInTheFuture() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        IdentityTokenIssuer issuer = new IdentityTokenIssuer(properties(), Clock.fixed(now, java.time.ZoneOffset.UTC));
        Instant future = now.plusSeconds(30);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer("http://gateway.test")
            .audience("campus-market-api")
            .subject(UUID.randomUUID().toString())
            .claim("roles", Set.of("ROLE_USER"))
            .issueTime(Date.from(future))
            .expirationTime(Date.from(future.plusSeconds(900)))
            .build();
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
            .keyID("identity-key-1").type(JOSEObjectType.JWT).build(), claims);
        token.sign(new RSASSASigner(TestRsaKeys.privateKey()));

        assertThatThrownBy(() -> issuer.parse(token.serialize()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyRolesAndUnsupportedRoles() {
        IdentityTokenIssuer issuer = new IdentityTokenIssuer(properties());
        assertThatThrownBy(() -> issuer.issue(new AuthenticatedUser(UUID.randomUUID(), Set.of())))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issuer.issue(new AuthenticatedUser(UUID.randomUUID(), Set.of("ROLE_HACK"))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesFixedTtl() {
        assertThat(new IdentityTokenIssuer(properties()).ttl()).isEqualTo(Duration.ofMinutes(15));
    }

    private static RsaKeyProperties properties() {
        return new RsaKeyProperties("http://gateway.test", "campus-market-api", "identity-key-1",
            privateKey(), publicKey());
    }

    private static FileSystemResource privateKey() {
        return new FileSystemResource(keys.resolve("test-private-key.pem"));
    }

    private static FileSystemResource publicKey() {
        return new FileSystemResource(keys.resolve("test-public-key.pem"));
    }

    private static Path assertDoesNotThrowTempDirectory() {
        try {
            return Files.createTempDirectory("identity-mismatched-keys");
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static String pem(byte[] der, String label) {
        return "-----BEGIN " + label + "-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
            + "\n-----END " + label + "-----\n";
    }
}
