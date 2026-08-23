package com.example.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OrderMqApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderMqApplication.class, args);
    }
}
