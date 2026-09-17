package com.example.campusmarket.legacy;

import com.example.campusmarket.support.JdbcSupportStatusRepository;
import com.example.campusmarket.support.SupportCursor;
import com.example.campusmarket.support.SupportStatusController;
import com.example.campusmarket.support.SupportStatusService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/** 兼容市场服务入口；显式限定扫描边界，避免装配身份服务生产实现。 */
@SpringBootApplication(scanBasePackages = {
    "com.example.campusmarket.api",
    "com.example.campusmarket.catalog",
    "com.example.campusmarket.dispute",
    "com.example.campusmarket.messaging",
    "com.example.campusmarket.observability",
    "com.example.campusmarket.order",
    "com.example.campusmarket.payment",
    "com.example.campusmarket.review",
    "com.example.campusmarket.shared",
    "com.example.campusmarket.storage",
    "com.example.campusmarket.warranty",
    "com.example.campusmarket.security"
})
@Import({JdbcSupportStatusRepository.class, SupportStatusService.class,
    SupportStatusController.class, SupportCursor.class})
@ConfigurationPropertiesScan(basePackages = {
    "com.example.campusmarket.api",
    "com.example.campusmarket.catalog",
    "com.example.campusmarket.dispute",
    "com.example.campusmarket.messaging",
    "com.example.campusmarket.observability",
    "com.example.campusmarket.order",
    "com.example.campusmarket.payment",
    "com.example.campusmarket.review",
    "com.example.campusmarket.shared",
    "com.example.campusmarket.storage",
    "com.example.campusmarket.warranty",
    "com.example.campusmarket.security"
})
public class LegacyMarketApplication {
    public static void main(String[] args) {
        SpringApplication.run(LegacyMarketApplication.class, args);
    }
}
