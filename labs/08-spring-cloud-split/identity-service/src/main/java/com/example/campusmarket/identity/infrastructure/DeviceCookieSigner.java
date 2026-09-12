package com.example.campusmarket.identity.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/** 对设备 cookie 做 HMAC 签名；伪造值不会成为可控限流维度。 */
@Component
@Profile("!test")
public class DeviceCookieSigner {
    private static final String ALGORITHM = "HmacSHA256";
    private static final int PAYLOAD_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] secret;

    public DeviceCookieSigner(@Value("${campus.market.identity.verification-secret}") String secret) {
        Objects.requireNonNull(secret, "设备签名密钥不能为空");
        byte[] encoded = secret.getBytes(StandardCharsets.UTF_8);
        if (encoded.length < 32) {
            throw new IllegalArgumentException("设备签名密钥至少需要 256 位");
        }
        this.secret = encoded.clone();
    }

    public String issue() {
        byte[] payload = new byte[PAYLOAD_BYTES];
        RANDOM.nextBytes(payload);
        String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        return encodedPayload + "." + signature(encodedPayload);
    }

    public boolean verify(String value) {
        if (value == null) {
            return false;
        }
        int separator = value.lastIndexOf('.');
        if (separator <= 0 || separator == value.length() - 1 || value.indexOf('.') != separator) {
            return false;
        }
        String payload = value.substring(0, separator);
        String supplied = value.substring(separator + 1);
        try {
            if (Base64.getUrlDecoder().decode(payload).length < PAYLOAD_BYTES) {
                return false;
            }
            byte[] expected = Base64.getUrlDecoder().decode(signature(payload));
            byte[] actual = Base64.getUrlDecoder().decode(supplied);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public String fallback(String remoteAddress) {
        String input = "invalid-device:" + (remoteAddress == null ? "unknown" : remoteAddress);
        return "fallback." + signature(input);
    }

    private String signature(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法签发设备 cookie", ex);
        }
    }
}
