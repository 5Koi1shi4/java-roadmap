package com.example.campusmarket.identity.infrastructure;

import com.example.campusmarket.identity.application.VerificationMailSender;
import com.example.campusmarket.identity.domain.CampusEmail;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** 仅供 local/test 使用的验证码适配器；生产 profile 不会装配此 Bean。 */
@Component
@Profile({"local", "test"})
public final class LocalVerificationMailSender implements VerificationMailSender {
    private final Map<String, String> latestCodes = new ConcurrentHashMap<>();

    @Override
    public void send(CampusEmail email, String code) {
        Objects.requireNonNull(email, "邮箱不能为空");
        if (code == null || !code.matches("\\d{6}")) {
            throw new IllegalArgumentException("验证码格式无效");
        }
        latestCodes.put(email.value(), code);
    }

    public String latestCode(CampusEmail email) {
        return email == null ? null : latestCodes.get(email.value());
    }

    public String latestCode(String email) {
        return email == null ? null : latestCodes.get(email);
    }
}
