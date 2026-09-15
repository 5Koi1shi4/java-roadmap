package com.example.campusmarket.security;

import com.example.campusmarket.testsupport.TestJwtFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Status;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.web.client.RestOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 交易服务不能在 JWKS 缓存或 Eureka 注册信息失效后永久报告就绪。 */
class ReadinessCacheExpiryTest {
    @Test
    void jwksOutageStopsBeingReadyAfterBoundedStaleWindow() {
        MutableClock clock = new MutableClock();
        AtomicReference<String> response = new AtomicReference<>(
            "{\"keys\":[" + TestJwtFactory.publicJwk("test-key-1").toJSONString() + "]}");
        RestOperations rest = mock(RestOperations.class);
        when(rest.getForObject("http://identity.test/jwks", String.class))
            .thenAnswer(ignored -> {
                if (response.get() == null) throw new IllegalStateException("identity unavailable");
                return response.get();
            });
        JwksReadinessHealthIndicator indicator = new JwksReadinessHealthIndicator(
            rest, "http://identity.test/jwks", clock, Duration.ofSeconds(30));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        response.set(null);
        clock.advance(Duration.ofSeconds(29));
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        clock.advance(Duration.ofSeconds(1));
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void missingIdentityInstanceOrOldRegistryFetchStopsEurekaReadiness() {
        @SuppressWarnings("unchecked")
        ObjectProvider<DiscoveryClient> discoveryProvider = mock(ObjectProvider.class);
        DiscoveryClient discovery = mock(DiscoveryClient.class);
        ServiceInstance instance = mock(ServiceInstance.class);
        MutableClock clock = new MutableClock();
        AtomicReference<Boolean> registryReachable = new AtomicReference<>(true);
        when(discoveryProvider.getIfAvailable()).thenReturn(discovery);
        when(discovery.getInstances("identity-service")).thenReturn(List.of(instance));
        EurekaReadinessHealthIndicator indicator = new EurekaReadinessHealthIndicator(
            discoveryProvider, registryReachable::get, clock, Duration.ofSeconds(45));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        registryReachable.set(false);
        clock.advance(Duration.ofSeconds(44));
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        clock.advance(Duration.ofSeconds(1));
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        registryReachable.set(true);
        when(discovery.getInstances("identity-service")).thenReturn(List.of());
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-15T00:00:00Z");

        private void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
