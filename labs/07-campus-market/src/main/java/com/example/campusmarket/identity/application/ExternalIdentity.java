package com.example.campusmarket.identity.application;

import java.util.Map;

public record ExternalIdentity(String provider, String subject, Map<String, String> attributes) {
    public ExternalIdentity {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
