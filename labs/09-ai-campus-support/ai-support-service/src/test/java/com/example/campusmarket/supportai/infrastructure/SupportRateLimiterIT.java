package com.example.campusmarket.supportai.infrastructure;

import com.example.campusmarket.supportai.application.AnswerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用真实 Redis 验证固定 20/10 桶及 Redis 故障边界。 */
@Testcontainers(disabledWithoutDocker = true)
class SupportRateLimiterIT {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
        DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    private LettuceConnectionFactory factory;

    @AfterEach
    void closeRedisClient() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    void anonymousIpAllowsExactlyTwentyRequestsPerMinute() {
        SupportRateLimiter limiter = limiter("anonymous-" + UUID.randomUUID());

        for (int i = 0; i < SupportRateLimiter.ANONYMOUS_REQUESTS_PER_MINUTE; i++) {
            assertThat(limiter.tryAcquire("198.51.100.10", null)).isTrue();
        }
        assertThat(limiter.tryAcquire("198.51.100.10", null)).isFalse();
    }

    @Test
    void privateUserAllowsExactlyTenRequestsPerMinute() {
        SupportRateLimiter limiter = limiter("private-" + UUID.randomUUID());
        UUID userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        for (int i = 0; i < SupportRateLimiter.PRIVATE_REQUESTS_PER_MINUTE; i++) {
            assertThat(limiter.tryAcquire("198.51.100.10", userId)).isTrue();
        }
        assertThat(limiter.tryAcquire("198.51.100.10", userId)).isFalse();
    }

    @Test
    void redisConnectionFailureIsDependencyUnavailable() {
        LettuceConnectionFactory unavailableFactory = new LettuceConnectionFactory(
            "127.0.0.1", 1);
        StringRedisTemplate unavailableRedis = new StringRedisTemplate(unavailableFactory);
        unavailableFactory.afterPropertiesSet();
        unavailableRedis.afterPropertiesSet();
        try {
            SupportRateLimiter limiter = new SupportRateLimiter(unavailableRedis,
                Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC),
                "failure-" + UUID.randomUUID());

            assertThatThrownBy(() -> limiter.tryAcquire("198.51.100.11", null))
                .isInstanceOf(AnswerService.DependencyUnavailableException.class);
        } finally {
            unavailableFactory.destroy();
        }
    }

    private SupportRateLimiter limiter(String prefix) {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        return new SupportRateLimiter(redis,
            Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC), prefix);
    }
}
