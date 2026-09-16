package com.example.campusmarket.integration;

import com.example.campusmarket.legacy.LegacyMarketApplication;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;

import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = LegacyMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "management.endpoint.health.group.readiness.include=readinessState")
@ActiveProfiles("test")
class ApplicationBaselineIT {
    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void startsWithTestProfileAndAllExternalAdaptersDisabled() {
        assertThat(applicationContext.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(RedisConnectionFactory.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(ConnectionFactory.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(ElasticsearchClient.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(Rest5Client.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(MinioClient.class)).isEmpty();
    }
}
