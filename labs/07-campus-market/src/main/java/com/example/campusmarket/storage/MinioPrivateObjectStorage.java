package com.example.campusmarket.storage;

import com.example.campusmarket.catalog.application.MediaStorage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
public class MinioPrivateObjectStorage implements PrivateObjectStorage, MediaStorage {
    private final MinioClient client;
    private final String bucket;
    private volatile boolean bucketReady;

    public MinioPrivateObjectStorage(
        @Value("${campus.market.storage.endpoint:http://localhost:9000}") String endpoint,
        @Value("${campus.market.storage.access-key:minioadmin}") String accessKey,
        @Value("${campus.market.storage.secret-key:minioadmin-local}") String secretKey,
        @Value("${campus.market.storage.bucket:campus-market}") String bucket) {
        this.client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.bucket = bucket;
    }

    @Override
    public void put(String objectKey, InputStream content, long sizeBytes, String contentType) {
        try {
            ensureBucket();
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey)
                .stream(content, sizeBytes, -1).contentType(contentType).build());
        } catch (Exception e) {
            throw new StorageUnavailableException("对象存储不可用", e);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code()) || "NoSuchObject".equals(e.errorResponse().code())) {
                throw new ObjectNotFoundException("媒体不存在");
            }
            throw new StorageUnavailableException("对象存储不可用", e);
        } catch (Exception e) {
            throw new StorageUnavailableException("对象存储不可用", e);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw new StorageUnavailableException("对象存储不可用", e);
        }
    }

    private void ensureBucket() throws Exception {
        if (bucketReady) return;
        synchronized (this) {
            if (!bucketReady) {
                if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                }
                bucketReady = true;
            }
        }
    }

    public static class StorageUnavailableException extends RuntimeException {
        public StorageUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    public static class ObjectNotFoundException extends RuntimeException {
        public ObjectNotFoundException(String message) { super(message); }
    }
}
