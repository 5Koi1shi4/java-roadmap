package com.example.campusmarket.identity.infrastructure;

import com.example.campusmarket.identity.application.VerificationMailSender;
import com.example.campusmarket.identity.domain.CampusEmail;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Local/test-only mail adapter. It intentionally has no production profile. */
@Component
@Profile({"local", "test"})
public class LocalVerificationMailSender implements VerificationMailSender {
    private final Map<String, String> latestCodes = new ConcurrentHashMap<>();

    @Override
    public void send(CampusEmail email, String code) {
        latestCodes.put(email.value(), code);
    }

    public String latestCode(CampusEmail email) {
        return latestCodes.get(email.value());
    }

    public String latestCode(String email) {
        return latestCodes.get(email);
    }
}
