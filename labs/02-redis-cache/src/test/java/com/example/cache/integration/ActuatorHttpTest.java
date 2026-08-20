package com.example.cache.integration;

import com.example.cache.CacheApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = CacheApplication.class,
        properties = {
                "MYSQL_HOST=mysql-service",
                "MYSQL_PORT=3310",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        }
)
@AutoConfigureMockMvc
class ActuatorHttpTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Environment environment;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private PlatformTransactionManager transactionManager;

    @Test
    void exposesTheCacheHitCounterThroughActuatorHttp() throws Exception {
        mvc.perform(get("/actuator/metrics/cache.hit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("cache.hit"))
                .andExpect(jsonPath("$.measurements[0].statistic").value("COUNT"));
    }

    @Test
    void resolvesDatasourceUrlWithMysqlHostEnvironmentVariable() {
        assertThat(environment.getProperty("spring.datasource.url"))
                .startsWith("jdbc:mysql://mysql-service:3310/");
    }
}
