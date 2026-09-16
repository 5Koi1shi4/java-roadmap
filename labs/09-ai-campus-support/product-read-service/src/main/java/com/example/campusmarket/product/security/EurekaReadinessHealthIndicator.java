package com.example.campusmarket.product.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.cloud.client.discovery.DiscoveryClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** 商品读服务 Eureka readiness：身份实例不可见或注册快照超界即停止接流量。 */
public final class EurekaReadinessHealthIndicator implements HealthIndicator {
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
    public Health health() {
        try {
            DiscoveryClient client = clientProvider.getIfAvailable();
            if (client == null || client.getInstances("identity-service").isEmpty()) {
                return Health.down().build();
            }
            if (Boolean.TRUE.equals(registryReachable.get())) {
                lastRegistrySuccess.set(clock.instant());
                return Health.up().build();
            }
        } catch (RuntimeException ignored) {
            // 注册中心失败只在最近一次成功检查的有限窗口内容忍。
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
