package com.example.files.config;

import com.example.files.application.cleanup.LocalTemporaryFallbackCleaner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 仅在本地对象存储启用临时目录兜底，MinIO 部署不触碰本机文件系统。 */
@Configuration(proxyBeanMethods = false)
public class LocalFallbackConfiguration {
    @Bean
    @ConditionalOnExpression("'${file.storage.type:local}' == 'local'")
    public LocalTemporaryFallbackCleaner localTemporaryFallbackCleaner(FileServiceProperties properties) {
        return new LocalTemporaryFallbackCleaner(properties);
    }
}
