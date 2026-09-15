package com.example.campusmarket.gateway.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Eureka readiness 同时检查可路由实例和注册中心；断线只容忍有限的旧注册快照。 */
public final class EurekaReadinessHealthIndicator implements ReactiveHealthIndicator {
    private static final Set<String> REQUIRED_SERVICES =
        Set.of("identity-service", "legacy-market-service");

    private final ObjectProvider<DiscoveryClient> clientProvider;
    private final Supplier<Boolean> registryReachable;
    private final Clock clock;
    private final Duration staleWindow;
    private final AtomicReference<Instant> lastRegistrySuccess = new AtomicReference<>();

    public EurekaReadinessHealthIndicator(ObjectProvider<DiscoveryClient> clientProvider,
                                          Supplier<Boolean> registryReachable) {
        this(clientProvider, registryReachable, Clock.systemUTC(), Duration.ofSeconds(45));
    }

    EurekaReadinessHealthIndicator(ObjectProvider<DiscoveryClient> clientProvider,
                                   Supplier<Boolean> registryReachable, Clock clock,
                                   Duration staleWindow) {
        this.clientProvider = Objects.requireNonNull(clientProvider, "DiscoveryClient provider 不能为空");
        this.registryReachable = Objects.requireNonNull(registryReachable, "注册中心检查不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.staleWindow = Objects.requireNonNull(staleWindow, "陈旧窗口不能为空");
        if (staleWindow.isNegative() || staleWindow.isZero()) {
            throw new IllegalArgumentException("陈旧窗口必须为正数");
        }
    }

    @Override
    public Mono<Health> health() {
        return Mono.fromCallable(this::check)
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorReturn(Health.down().build());
    }

    private Health check() {
        DiscoveryClient client = clientProvider.getIfAvailable();
        if (client == null || REQUIRED_SERVICES.stream()
            .anyMatch(service -> client.getInstances(service).isEmpty())) {
            return Health.down().build();
        }
        try {
            if (Boolean.TRUE.equals(registryReachable.get())) {
                lastRegistrySuccess.set(clock.instant());
                return Health.up().build();
            }
        } catch (RuntimeException ignored) {
            // 只在注册中心探测失败时使用此前确认过的有限缓存窗口。
        }
        return recentRegistrySuccess() ? Health.up().build() : Health.down().build();
    }

    private boolean recentRegistrySuccess() {
        Instant previous = lastRegistrySuccess.get();
        Instant now = clock.instant();
        return previous != null && !now.isBefore(previous)
            && now.isBefore(previous.plus(staleWindow));
    }
}
