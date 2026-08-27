package com.example.files.integration;

import com.example.files.config.IdentityConfigurationGuard;
import com.example.files.config.TrustedHeaderIdentityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityConfigurationIT {
    @Test
    void trustedHeaderOutsideLocalOrTestFailsAtStartup() {
        new ApplicationContextRunner()
            .withUserConfiguration(TrustedHeaderIdentityConfiguration.class, IdentityConfigurationGuard.class)
            .withPropertyValues("spring.profiles.active=prod", "file.identity.trusted-header-enabled=true")
            .run(context -> assertThat(context.getStartupFailure())
                .hasMessageContaining("trusted header identity is limited to local/test"));
    }

    @Test
    void trustedHeaderRequiresExplicitSwitchAndAllowedProfile() {
        new ApplicationContextRunner()
            .withUserConfiguration(TrustedHeaderIdentityConfiguration.class, IdentityConfigurationGuard.class)
            .withPropertyValues("spring.profiles.active=test", "file.identity.trusted-header-enabled=true")
            .run(context -> {
                assertThat(context.getStartupFailure()).isNull();
                assertThat(context).hasSingleBean(com.example.files.api.security.RequesterIdentityResolver.class);
            });
    }

    @Test
    void noIdentityResolverFailsClosed() {
        new ApplicationContextRunner()
            .withUserConfiguration(IdentityConfigurationGuard.class)
            .withPropertyValues("spring.profiles.active=test")
            .run(context -> assertThat(context.getStartupFailure())
                .hasMessageContaining("no RequesterIdentityResolver bean configured"));
    }
}
