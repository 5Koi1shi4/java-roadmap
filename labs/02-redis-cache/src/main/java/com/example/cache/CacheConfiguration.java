package com.example.cache;

import com.example.cache.application.MicrometerCacheMetrics;
import com.example.cache.application.ProductQueryService;
import com.example.cache.application.RebuildLock;
import com.example.cache.domain.ProductCache;
import com.example.cache.domain.ProductRepository;
import com.example.cache.infrastructure.JdbcProductRepository;
import com.example.cache.infrastructure.RedisProductCache;
import com.example.cache.infrastructure.RedissonRebuildLock;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class CacheConfiguration {

    @Bean
    ProductRepository productRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcProductRepository(jdbcTemplate);
    }

    @Bean
    ProductCache productCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        return new RedisProductCache(redisTemplate, objectMapper);
    }

    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port
    ) {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + host + ":" + port);
        return Redisson.create(config);
    }

    @Bean
    RebuildLock rebuildLock(RedissonClient client) {
        return new RedissonRebuildLock(client);
    }

    @Bean
    ProductQueryService productQueryService(
            ProductRepository repository,
            ProductCache cache,
            RebuildLock rebuildLock,
            MeterRegistry meterRegistry
    ) {
        return new ProductQueryService(repository, cache, rebuildLock, new MicrometerCacheMetrics(meterRegistry));
    }
}
