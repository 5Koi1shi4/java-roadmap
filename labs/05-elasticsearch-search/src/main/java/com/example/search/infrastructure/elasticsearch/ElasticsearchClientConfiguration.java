package com.example.search.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration
public class ElasticsearchClientConfiguration {
    @Bean(destroyMethod = "close")
    RestClient elasticsearchRestClient(@Value("${spring.elasticsearch.uris}") String uri) {
        URI endpoint = URI.create(uri);
        return RestClient.builder(new HttpHost(endpoint.getHost(),
                endpoint.getPort() > 0 ? endpoint.getPort() : endpoint.getScheme().equals("https") ? 443 : 80,
                endpoint.getScheme())).build();
    }

    @Bean
    RestClientTransport elasticsearchTransport(RestClient restClient, ObjectMapper objectMapper) {
        return new RestClientTransport(restClient, new JacksonJsonpMapper(objectMapper));
    }

    @Bean
    ElasticsearchClient elasticsearchClient(RestClientTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
