package com.example.campusmarket.identity.security;

import com.example.campusmarket.identity.application.AuthenticatedUser;
import com.example.campusmarket.testsupport.TestRsaKeys;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
}
