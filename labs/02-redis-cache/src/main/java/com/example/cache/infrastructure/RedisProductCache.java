package com.example.cache.infrastructure;

import com.example.cache.domain.Product;
import com.example.cache.domain.ProductCache;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

public final class RedisProductCache implements ProductCache {

    private static final String KEY_PREFIX = "product:v1:";
    private static final String NEGATIVE = "NEGATIVE";
    private static final Duration PRODUCT_TTL = Duration.ofMinutes(10);
    private static final Duration NEGATIVE_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisProductCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public CacheLookup get(long id) {
        String stored = redisTemplate.opsForValue().get(key(id));
        if (stored == null) {
            return CacheLookup.miss();
        }
        if (NEGATIVE.equals(stored)) {
            return CacheLookup.negative();
        }
        try {
            return CacheLookup.product(objectMapper.readValue(stored, Product.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot deserialize cached product", exception);
        }
    }

    @Override
    public void put(Product product) {
        try {
            redisTemplate.opsForValue().set(key(product.id()), objectMapper.writeValueAsString(product), PRODUCT_TTL);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize product for cache", exception);
        }
    }

    @Override
    public void putNegative(long id) {
        redisTemplate.opsForValue().set(key(id), NEGATIVE, NEGATIVE_TTL);
    }

    @Override
    public void evict(long id) {
        redisTemplate.delete(key(id));
    }

    private String key(long id) {
        return KEY_PREFIX + id;
    }
}
