package com.example.campusmarket.testsupport;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestJwtFactoryTest {

    @Test
    void issuesRs256TokenWithExactIdentityContract() throws Exception {
        UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        String token = TestJwtFactory.issue(userId, Set.of("ROLE_USER"),
                Instant.parse("2026-09-11T00:00:00Z"), Duration.ofMinutes(15),
                "http://gateway.test", "campus-market-api", "test-key-1");

        SignedJWT jwt = SignedJWT.parse(token);
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("test-key-1");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("campus-market-api");
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant())
                .isEqualTo(Instant.parse("2026-09-11T00:15:00Z"));
    }

    @Test
    void recordsStandardClaimsAndSortedRoles() throws Exception {
        UUID userId = UUID.randomUUID();
        Instant issuedAt = Instant.parse("2026-09-11T00:00:00Z");

        String token = TestJwtFactory.issue(userId,
                new LinkedHashSet<>(List.of("ROLE_USER", "ROLE_ADMIN")), issuedAt,
                Duration.ofMinutes(15), "http://gateway.test", "campus-market-api", "test-key-1");

        SignedJWT jwt = SignedJWT.parse(token);
        assertThat(jwt.getHeader().getType()).isEqualTo(JOSEObjectType.JWT);
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("http://gateway.test");
        assertThat(jwt.getJWTClaimsSet().getIssueTime()).isEqualTo(Date.from(issuedAt));
        assertThat(jwt.getJWTClaimsSet().getExpirationTime()).isEqualTo(Date.from(issuedAt.plus(Duration.ofMinutes(15))));
        assertThat(jwt.getJWTClaimsSet().getStringListClaim("roles"))
                .containsExactly("ROLE_ADMIN", "ROLE_USER");
        assertThat(jwt.verify(new RSASSAVerifier(TestRsaKeys.publicKey()))).isTrue();
    }

    @Test
    void exposesOnlyPublicJwkMaterial() {
        RSAKey jwk = TestJwtFactory.publicJwk("test-key-1");

        assertThat(jwk.getKeyID()).isEqualTo("test-key-1");
        assertThat(jwk.getKeyUse()).isEqualTo(KeyUse.SIGNATURE);
        assertThat(jwk.getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwk.isPrivate()).isFalse();
        assertThat(jwk.getModulus()).isNotNull();
        assertThat(jwk.getPublicExponent()).isNotNull();
    }

    @Test
    void generatesOne2048BitKeyPairAndWritesPemOnlyToRequestedDirectory(@TempDir Path directory) throws Exception {
        assertThat(TestRsaKeys.privateKey().getModulus().bitLength()).isEqualTo(2048);
        assertThat(TestRsaKeys.publicKey().getModulus()).isEqualTo(TestRsaKeys.privateKey().getModulus());

        TestRsaKeys.writePemPair(directory);

        Path privatePem = directory.resolve("test-private-key.pem");
        Path publicPem = directory.resolve("test-public-key.pem");
        assertThat(Files.exists(privatePem)).isTrue();
        assertThat(Files.exists(publicPem)).isTrue();
        assertThat(Files.readString(privatePem)).contains("PRIVATE", "KEY");
        assertThat(Files.readString(publicPem)).contains("PUBLIC", "KEY");
    }

    @Test
    void assertsExplicitJsonUtf8ContentType() {
        HttpAssertions.assertJsonUtf8(response("application/json; charset=UTF-8"));

        assertThatThrownBy(() -> HttpAssertions.assertJsonUtf8(response("application/json")))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void rejectsInvalidJwtArguments() {
        UUID userId = UUID.randomUUID();
        Instant issuedAt = Instant.parse("2026-09-11T00:00:00Z");
        Set<String> roles = Set.of("ROLE_USER");

        assertThatThrownBy(() -> TestJwtFactory.issue(null, roles, issuedAt,
                Duration.ofMinutes(15), "issuer", "audience", "kid"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TestJwtFactory.issue(userId, null, issuedAt,
                Duration.ofMinutes(15), "issuer", "audience", "kid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestJwtFactory.issue(userId, roles, issuedAt,
                Duration.ofMinutes(14), "issuer", "audience", "kid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestJwtFactory.issue(userId, Set.of("ROLE_ROOT"), issuedAt,
                Duration.ofMinutes(15), "issuer", "audience", "kid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestJwtFactory.issue(userId, roles, issuedAt,
                Duration.ofMinutes(15), "issuer", "audience", "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestJwtFactory.issue(userId, roles, issuedAt,
                Duration.ofMinutes(15), "", "audience", "kid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestJwtFactory.issue(userId, roles, issuedAt,
                Duration.ofMinutes(15), "issuer", "", "kid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestJwtFactory.publicJwk(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TestJwtFactory.publicJwk("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestRsaKeys.writePemPair(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static HttpResponse<String> response(String contentType) {
        HttpHeaders headers = HttpHeaders.of(Map.of("Content-Type", List.of(contentType)),
                (name, value) -> true);
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return 200;
            }

            @Override
            public HttpRequest request() {
                return HttpRequest.newBuilder(URI.create("http://gateway.test")).build();
            }

            @Override
            public Optional<HttpResponse<String>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return headers;
            }

            @Override
            public String body() {
                return "{}";
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return URI.create("http://gateway.test");
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }
}
