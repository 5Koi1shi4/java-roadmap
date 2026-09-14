package com.example.campusmarket.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Gateway 单元启动不会因旅程服务的数据库依赖创建 DataSource。 */
class GatewayStartupIsolationTest {

    @Test
    void startsWithoutDatabaseBeansWhenServiceTestClasspathIsPresent() {
        new ReactiveWebApplicationContextRunner()
            .withUserConfiguration(ApiGatewayApplication.class)
            .withPropertyValues(
                "eureka.client.enabled=false",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://gateway.test")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean("dataSource");
            });
    }
}
