package com.example.search.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.search.config.SearchProperties;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration
public class ElasticsearchClientConfiguration {
    @Bean(destroyMethod = "close")
    RestClient elasticsearchRestClient(@Value("${spring.elasticsearch.uris}") String uri,
                                       SearchProperties properties) {
        URI endpoint = URI.create(uri);
        return RestClient.builder(new HttpHost(endpoint.getHost(),
                endpoint.getPort() > 0 ? endpoint.getPort() : endpoint.getScheme().equals("https") ? 443 : 80,
                endpoint.getScheme()))
                .setRequestConfigCallback(current -> current
                        .setConnectTimeout(timeoutMillis(properties.requestTimeout()))
                        .setConnectionRequestTimeout(timeoutMillis(properties.requestTimeout()))
                        .setSocketTimeout(timeoutMillis(properties.requestTimeout())))
                .build();
    }

    @Bean
    RestClientTransport elasticsearchTransport(RestClient restClient, ObjectMapper objectMapper) {
        return new RestClientTransport(restClient, new JacksonJsonpMapper(objectMapper));
    }

    @Bean
    ElasticsearchClient elasticsearchClient(RestClientTransport transport) {
        return new ElasticsearchClient(transport);
    }

    private static int timeoutMillis(java.time.Duration timeout) {
        long millis = timeout.toMillis();
        if (timeout.toNanosPart() % 1_000_000 != 0) millis++;
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, millis));
    }
}
