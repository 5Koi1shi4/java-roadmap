package com.example.campusmarket.gateway;

import com.example.campusmarket.testsupport.HttpAssertions;
import com.example.campusmarket.testsupport.TestJwtFactory;
import com.example.campusmarket.testsupport.TestRsaKeys;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证真实消费者可以通过 test scope 的 tests classifier 使用平台 JWT 夹具。
 */
class PlatformTestSupportConsumerSmokeTest {

    @Test
    void consumesTestJarJwtFixtureFromApiGateway() throws Exception {
        UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        String token = TestJwtFactory.issue(userId, Set.of("ROLE_USER"),
                Instant.parse("2026-09-11T00:00:00Z"), Duration.ofMinutes(15),
                "http://gateway.test", "campus-market-api", "test-key-1");
        SignedJWT jwt = SignedJWT.parse(token);

        assertEquals(userId.toString(), jwt.getJWTClaimsSet().getSubject());
        assertTrue(jwt.verify(new RSASSAVerifier(TestRsaKeys.publicKey())));

        HttpResponse<?> response = mock(HttpResponse.class);
        when(response.headers()).thenReturn(HttpHeaders.of(
                Map.of("Content-Type", List.of("application/json; charset=UTF-8")),
                (name, value) -> true));
        HttpAssertions.assertJsonUtf8(response);
    }
}
