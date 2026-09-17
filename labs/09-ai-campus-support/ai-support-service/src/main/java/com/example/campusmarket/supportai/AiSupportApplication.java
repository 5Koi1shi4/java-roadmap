package com.example.campusmarket.supportai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Map;

/** AI 校园支持服务启动入口。 */
@SpringBootApplication
public class AiSupportApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AiSupportApplication.class);
        application.setDefaultProperties(Map.of("spring.application.name", "ai-support-service"));
        application.run(args);
    }
}
