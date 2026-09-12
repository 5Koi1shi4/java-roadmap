package com.example.campusmarket.identity;

import com.example.campusmarket.identity.security.RsaKeyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 独立身份领域服务启动类。 */
@SpringBootApplication
@EnableConfigurationProperties(RsaKeyProperties.class)
public class IdentityServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
