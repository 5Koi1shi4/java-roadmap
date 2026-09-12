package com.example.campusmarket.identity.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;

/**
 * 身份服务签名密钥配置。
 *
 * <p>密钥只从 Spring {@link Resource} 读取，不在该类中生成或写回文件。构造时一次性
 * 读取并校验密钥，避免服务启动后才发现公私钥不匹配。</p>
 */
@ConfigurationProperties("campus.market.jwt")
public record RsaKeyProperties(String issuer, String audience, String keyId,
                               Resource privateKey, Resource publicKey) {
    public static final String REQUIRED_AUDIENCE = "campus-market-api";

    public RsaKeyProperties {
        requireText(issuer, "JWT issuer");
        if (!REQUIRED_AUDIENCE.equals(audience)) {
            throw new IllegalArgumentException("JWT audience must be campus-market-api");
        }
        requireText(keyId, "JWT key id");
        if (keyId.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("JWT key id must not contain whitespace");
        }
        Objects.requireNonNull(privateKey, "JWT private key resource is required");
        Objects.requireNonNull(publicKey, "JWT public key resource is required");
        RSAPrivateKey parsedPrivate = readPrivateKey(privateKey);
        RSAPublicKey parsedPublic = readPublicKey(publicKey);
        if (!parsedPrivate.getModulus().equals(parsedPublic.getModulus())) {
            throw new IllegalArgumentException("JWT RSA key pair does not match");
        }
    }

    /** 读取并解析 PKCS#8 RSA 私钥。 */
    public static RSAPrivateKey readPrivateKey(Resource resource) {
        byte[] encoded = readEncoded(resource, "JWT private key");
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (GeneralSecurityException | ClassCastException ex) {
            throw new IllegalArgumentException("JWT private key is not a readable RSA PKCS#8 key", ex);
        }
    }

    /** 读取并解析 X.509 RSA 公钥。 */
    public static RSAPublicKey readPublicKey(Resource resource) {
        byte[] encoded = readEncoded(resource, "JWT public key");
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException | ClassCastException ex) {
            throw new IllegalArgumentException("JWT public key is not a readable RSA X.509 key", ex);
        }
    }

    private static byte[] readEncoded(Resource resource, String label) {
        Objects.requireNonNull(resource, label + " resource is required");
        try {
            if (!resource.exists() || !resource.isReadable()) {
                throw new IllegalArgumentException(label + " resource is not readable");
            }
            try (InputStream input = resource.getInputStream()) {
                byte[] bytes = input.readAllBytes();
                if (bytes.length == 0) {
                    throw new IllegalArgumentException(label + " is empty");
                }
                return decodePemOrDer(bytes, label);
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException(label + " resource is not readable", ex);
        }
    }

    private static byte[] decodePemOrDer(byte[] bytes, String label) {
        String text = new String(bytes, StandardCharsets.US_ASCII).trim();
        if (!text.startsWith("-----BEGIN ")) {
            return bytes.clone();
        }
        int beginEnd = text.indexOf('\n');
        int endStart = text.indexOf("-----END ");
        if (beginEnd < 0 || endStart <= beginEnd) {
            throw new IllegalArgumentException(label + " PEM armor is invalid");
        }
        String body = text.substring(beginEnd + 1, endStart).replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(label + " PEM body is invalid", ex);
        }
    }

    private static void requireText(String value, String label) {
        Objects.requireNonNull(value, label + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }
}
