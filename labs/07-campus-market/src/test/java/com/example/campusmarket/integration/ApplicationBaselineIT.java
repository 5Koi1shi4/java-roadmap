package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationBaselineIT {
    @Test
    void startsWithTestProfileAndAllExternalAdaptersDisabled() {
        new ApplicationContextRunner()
            .withUserConfiguration(CampusMarketApplication.class)
            .withPropertyValues(
                "spring.profiles.active=test",
                "spring.autoconfigure.exclude="
                    + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration")
            .run(context -> assertThat(context).hasNotFailed());
    }
}
