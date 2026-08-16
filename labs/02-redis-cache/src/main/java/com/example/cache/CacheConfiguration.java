package com.example.cache;

import com.example.cache.application.MicrometerCacheMetrics;
import com.example.cache.application.ProductQueryService;
import com.example.cache.domain.ProductCache;
import com.example.cache.domain.ProductRepository;
import com.example.cache.infrastructure.JdbcProductRepository;
import com.example.cache.infrastructure.RedisProductCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
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

    @Bean
    ProductQueryService productQueryService(
            ProductRepository repository,
            ProductCache cache,
            MeterRegistry meterRegistry
    ) {
        return new ProductQueryService(repository, cache, new MicrometerCacheMetrics(meterRegistry));
    }
}
