package com.example.campusmarket.identity.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Prevents a simulated or absent mail adapter from accidentally reaching production. */
@Configuration
@Profile("!local & !test")
public class IdentityProductionGuard {
    public IdentityProductionGuard(
        @Value("${campus.market.mail.simulation-enabled:false}") boolean simulationEnabled,
        @Value("${campus.market.mail.sender:}") String sender) {
        if (simulationEnabled || sender == null || sender.isBlank()) {
            throw new IllegalStateException("A production mail sender must be configured; simulation is disabled");
        }
    }
}
