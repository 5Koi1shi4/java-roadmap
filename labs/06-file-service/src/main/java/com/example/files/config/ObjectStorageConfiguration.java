package com.example.files.config;

import com.example.files.infrastructure.storage.LocalObjectStorage;
import com.example.files.infrastructure.storage.MinioObjectStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

/** 按配置只组装一个对象存储适配器；错误配置在启动阶段失败。 */
@Configuration(proxyBeanMethods = false)
public class ObjectStorageConfiguration {
    @Bean
    @ConditionalOnExpression("'${file.storage.type:local}' == 'local'")
    public LocalObjectStorage localObjectStorage(FileServiceProperties properties) {
        return new LocalObjectStorage(properties.storage());
    }

    @Bean
    @ConditionalOnExpression("'${file.storage.type:local}' == 'minio'")
    public MinioObjectStorage minioObjectStorage(FileServiceProperties properties) {
        MinioObjectStorage minio = new MinioObjectStorage(properties.storage(), properties.download().maxLinkTtl());
        minio.initialize();
        return minio;
    }
}
