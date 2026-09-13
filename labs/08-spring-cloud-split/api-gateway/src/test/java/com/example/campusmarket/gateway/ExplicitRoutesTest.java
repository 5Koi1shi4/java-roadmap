package com.example.campusmarket.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Gateway 只暴露设计中声明的两条服务路由。 */
@SpringBootTest(
    classes = ApiGatewayApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "eureka.client.enabled=false"
    })
class ExplicitRoutesTest {

    @Autowired
    private RouteDefinitionLocator routeDefinitions;

    @Autowired
    private Environment environment;

    @Test
    void exposesOnlyTwoExplicitServiceRoutes() {
        List<RouteDefinition> routes = routeDefinitions.getRouteDefinitions()
            .collectList()
            .block(Duration.ofSeconds(5));

        assertThat(routes).isNotNull().extracting(RouteDefinition::getId)
            .containsExactly("identity-api", "legacy-api");
        assertThat(routes.get(0).getUri().toString()).isEqualTo("lb://identity-service");
        assertThat(routes.get(1).getUri().toString()).isEqualTo("lb://legacy-market-service");
        assertThat(environment.getProperty(
            "spring.cloud.gateway.discovery.locator.enabled", Boolean.class)).isFalse();
    }
}
