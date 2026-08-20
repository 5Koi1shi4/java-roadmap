package com.example.cache.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisRebuildLockTest {

    @Test
    void doesNotAttemptSetNxAgainAfterSleepingPastTheAcquisitionDeadline() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq("lock:product:rebuild:7"), anyString(), eq(Duration.ofSeconds(3))))
                .thenReturn(false);
        AtomicLong elapsedNanos = new AtomicLong();
        RedisRebuildLock rebuildLock = new RedisRebuildLock(
                redisTemplate,
                elapsedNanos::get,
                ignored -> elapsedNanos.set(Duration.ofMillis(200).toNanos()));

        assertThat(rebuildLock.tryAcquire(7L)).isEmpty();

        verify(valueOperations, times(1)).setIfAbsent(
                eq("lock:product:rebuild:7"), anyString(), eq(Duration.ofSeconds(3)));
    }
}
