package com.example.files.config;

import com.example.files.application.upload.ObjectStorage;
import com.example.files.infrastructure.storage.LocalObjectStorage;
import com.example.files.infrastructure.storage.MinioObjectStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 按配置只组装一个对象存储适配器；错误配置在启动阶段失败。 */
@Configuration(proxyBeanMethods = false)
public class ObjectStorageConfiguration {
    @Bean
    public ObjectStorage objectStorage(FileServiceProperties properties) {
        FileServiceProperties.Storage storage = properties.storage();
        if ("local".equals(storage.type())) return new LocalObjectStorage(storage);
        if ("minio".equals(storage.type())) {
            MinioObjectStorage minio = new MinioObjectStorage(storage, properties.download().maxLinkTtl());
            minio.initialize();
            return minio;
        }
        throw new IllegalStateException("unsupported object storage type");
    }
}
