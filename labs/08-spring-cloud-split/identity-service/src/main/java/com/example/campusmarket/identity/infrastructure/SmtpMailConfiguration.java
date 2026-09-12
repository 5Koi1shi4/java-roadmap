package com.example.campusmarket.identity.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** 仅在非 local/test profile 创建生产 SMTP 客户端，配置缺失时启动即失败。 */
@Configuration(proxyBeanMethods = false)
@Profile("!local & !test")
@EnableConfigurationProperties(SmtpMailProperties.class)
public class SmtpMailConfiguration {
    @Bean
    JavaMailSender identityJavaMailSender(SmtpMailProperties properties) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.host());
        sender.setPort(properties.port());
        sender.setUsername(properties.username());
        sender.setPassword(properties.password());
        sender.getJavaMailProperties().put("mail.smtp.auth", String.valueOf(properties.username() != null));
        sender.getJavaMailProperties().put("mail.smtp.starttls.enable", "true");
        return sender;
    }
}
