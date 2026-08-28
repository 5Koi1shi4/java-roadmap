package com.example.files.config;

import com.example.files.infrastructure.storage.LocalObjectStorage;
import com.example.files.infrastructure.storage.MinioObjectStorage;
import com.example.files.application.audit.FileServiceMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 按配置只组装一个对象存储适配器；错误配置在启动阶段失败。 */
@Configuration(proxyBeanMethods = false)
public class ObjectStorageConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "file.storage", name = "type", havingValue = "local", matchIfMissing = true)
    public LocalObjectStorage localObjectStorage(FileServiceProperties properties,
                                                 org.springframework.beans.factory.ObjectProvider<FileServiceMetrics> metrics) {
        return new LocalObjectStorage(properties.storage(), metrics.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(prefix = "file.storage", name = "type", havingValue = "minio")
    public MinioObjectStorage minioObjectStorage(FileServiceProperties properties,
                                                 org.springframework.beans.factory.ObjectProvider<FileServiceMetrics> metrics) {
        MinioObjectStorage minio = new MinioObjectStorage(properties.storage(), properties.download().maxLinkTtl(), metrics.getIfAvailable());
        minio.initialize();
        return minio;
    }
}
