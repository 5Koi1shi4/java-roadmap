package com.example.campusmarket.product.infrastructure;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.elasticsearch.autoconfigure.Rest5ClientBuilderCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** 让 Elasticsearch 黑洞连接也在配置的 socket timeout 内结束请求。 */
@Configuration(proxyBeanMethods = false)
public class ElasticsearchRequestTimeoutConfiguration {

    @Bean
    Rest5ClientBuilderCustomizer elasticsearchResponseTimeout(
            @Value("${spring.elasticsearch.socket-timeout:30s}") Duration socketTimeout) {
        Timeout timeout = Timeout.of(socketTimeout);
        return new Rest5ClientBuilderCustomizer() {
            @Override
            public void customize(co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder builder) {
                // Rest5ClientBuilderCustomizer 要求实现该入口；请求超时由下方专用回调配置。
            }

            @Override
            public void customize(RequestConfig.Builder builder) {
                builder.setConnectionRequestTimeout(timeout);
                builder.setResponseTimeout(timeout);
            }
        };
    }
}
