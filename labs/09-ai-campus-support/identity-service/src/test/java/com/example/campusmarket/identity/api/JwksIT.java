package com.example.campusmarket.identity.api;

import com.example.campusmarket.identity.security.IdentityTokenIssuer;
import com.example.campusmarket.identity.security.RsaKeyProperties;
import com.example.campusmarket.testsupport.TestRsaKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JwksIT {
    private static Path keys;

    @BeforeAll
    static void setUp() throws Exception {
        keys = Files.createTempDirectory("identity-jwks");
        TestRsaKeys.writePemPair(keys);
    }

    @Test
    void publishesOnlyPublicRs256JwkMaterial() throws Exception {
        IdentityTokenIssuer issuer = new IdentityTokenIssuer(new RsaKeyProperties(
            "http://gateway.test", "campus-market-api", "identity-key-1",
            new FileSystemResource(keys.resolve("test-private-key.pem")),
            new FileSystemResource(keys.resolve("test-public-key.pem"))));
        JwksController controller = new JwksController(issuer);

        JsonNode root = new ObjectMapper().valueToTree(controller.jwks().getBody());
        JsonNode key = root.get("keys").get(0);

        assertThat(key.fieldNames()).toIterable().containsExactlyInAnyOrder("kty", "kid", "use", "alg", "n", "e");
        assertThat(key.get("kty").asText()).isEqualTo("RSA");
        assertThat(key.get("kid").asText()).isEqualTo("identity-key-1");
        assertThat(key.get("use").asText()).isEqualTo("sig");
        assertThat(key.get("alg").asText()).isEqualTo("RS256");
        assertThat(key.has("d")).isFalse();
        assertThat(key.has("p")).isFalse();
        assertThat(key.has("q")).isFalse();
        assertThat(key.toString()).doesNotContain("BEGIN", "PRIVATE", "PUBLIC");
    }
}
