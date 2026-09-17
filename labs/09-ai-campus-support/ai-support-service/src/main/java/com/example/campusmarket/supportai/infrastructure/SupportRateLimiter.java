package com.example.campusmarket.supportai.infrastructure;

import com.example.campusmarket.supportai.application.AnswerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 使用 Redis 原子计数实现固定分钟桶限流。 */
@Component
public final class SupportRateLimiter {
    public static final int ANONYMOUS_REQUESTS_PER_MINUTE = 20;
    public static final int PRIVATE_REQUESTS_PER_MINUTE = 10;
    public static final Duration BUCKET = Duration.ofMinutes(1);

    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>(
        "local count = redis.call('INCR', KEYS[1]); "
            + "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end; "
            + "if count <= tonumber(ARGV[1]) then return 1 else return 0 end", Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final String keyPrefix;

    @Autowired
    public SupportRateLimiter(StringRedisTemplate redis,
                              @Value("${campus.market.support.rate-limit.key-prefix:campus:ai:support:rate}")
                              String keyPrefix) {
        this(redis, Clock.systemUTC(), keyPrefix);
    }

    /** 供固定时钟的限流测试使用。 */
    public SupportRateLimiter(StringRedisTemplate redis, Clock clock) {
        this(redis, clock, "campus:ai:support:rate");
    }

    public SupportRateLimiter(StringRedisTemplate redis, Clock clock, String keyPrefix) {
        this.redis = Objects.requireNonNull(redis, "Redis 客户端不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
        Objects.requireNonNull(keyPrefix, "限流键前缀不能为空");
        if (keyPrefix.isBlank()) {
            throw new IllegalArgumentException("限流键前缀不能为空");
        }
        this.keyPrefix = keyPrefix.trim();
    }

    /** 申请一次请求额度；Redis 故障会被归类为依赖不可用。 */
    public boolean tryAcquire(String remoteIp, UUID privateUserId) {
        String dimension;
        int limit;
        if (privateUserId != null) {
            dimension = "user:" + privateUserId;
            limit = PRIVATE_REQUESTS_PER_MINUTE;
        } else {
            dimension = "ip:" + (remoteIp == null || remoteIp.isBlank() ? "unknown" : remoteIp);
            limit = ANONYMOUS_REQUESTS_PER_MINUTE;
        }
        long bucket = Instant.now(clock).getEpochSecond() / BUCKET.toSeconds();
        String key = keyPrefix + ":" + dimensionType(privateUserId) + ":"
            + digest(dimension) + ":" + bucket;
        try {
            Long allowed = redis.execute(RESERVE, List.of(key), String.valueOf(limit),
                String.valueOf(BUCKET.toSeconds() + 1));
            return Long.valueOf(1L).equals(allowed);
        } catch (RuntimeException exception) {
            throw new AnswerService.DependencyUnavailableException("限流服务暂时不可用");
        }
    }

    public boolean reserve(String remoteIp, UUID privateUserId) {
        return tryAcquire(remoteIp, privateUserId);
    }

    public void check(String remoteIp, UUID privateUserId) {
        if (!tryAcquire(remoteIp, privateUserId)) {
            throw new AnswerService.RateLimitExceededException();
        }
    }

    private static String dimensionType(UUID privateUserId) {
        return privateUserId == null ? "ip" : "user";
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }
}
