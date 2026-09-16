package com.example.campusmarket.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** API Gateway 启动入口。 */
@SpringBootApplication(excludeName =
    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
