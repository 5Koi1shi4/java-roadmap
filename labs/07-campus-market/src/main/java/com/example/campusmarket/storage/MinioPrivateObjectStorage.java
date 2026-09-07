package com.example.campusmarket.storage;

import com.example.campusmarket.catalog.application.MediaStorage;
import com.example.campusmarket.observability.CampusMetrics;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class MinioPrivateObjectStorage implements PrivateObjectStorage, MediaStorage {
    private final MinioClient client;
    private final String bucket;
    private volatile boolean bucketReady;
    private final CampusMetrics metrics;

    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MAX_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MAX_WRITE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MAX_CALL_TIMEOUT = Duration.ofSeconds(45);

    public MinioPrivateObjectStorage(
        @Value("${campus.market.storage.endpoint:http://localhost:9000}") String endpoint,
        @Value("${campus.market.storage.access-key:minioadmin}") String accessKey,
        @Value("${campus.market.storage.secret-key:minioadmin-local}") String secretKey,
        @Value("${campus.market.storage.bucket:campus-market}") String bucket,
        @Value("${campus.market.storage.connect-timeout:3s}") Duration connectTimeout,
        @Value("${campus.market.storage.read-timeout:10s}") Duration readTimeout,
        @Value("${campus.market.storage.write-timeout:10s}") Duration writeTimeout,
        @Value("${campus.market.storage.call-timeout:15s}") Duration callTimeout) {
        this(endpoint, accessKey, secretKey, bucket, connectTimeout, readTimeout, writeTimeout, callTimeout, null);
    }

    @Autowired
    public MinioPrivateObjectStorage(
        @Value("${campus.market.storage.endpoint:http://localhost:9000}") String endpoint,
        @Value("${campus.market.storage.access-key:minioadmin}") String accessKey,
        @Value("${campus.market.storage.secret-key:minioadmin-local}") String secretKey,
        @Value("${campus.market.storage.bucket:campus-market}") String bucket,
        @Value("${campus.market.storage.connect-timeout:3s}") Duration connectTimeout,
        @Value("${campus.market.storage.read-timeout:10s}") Duration readTimeout,
        @Value("${campus.market.storage.write-timeout:10s}") Duration writeTimeout,
        @Value("${campus.market.storage.call-timeout:15s}") Duration callTimeout,
        CampusMetrics metrics) {
        okhttp3.OkHttpClient httpClient = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(bounded(connectTimeout, MAX_CONNECT_TIMEOUT))
            .readTimeout(bounded(readTimeout, MAX_READ_TIMEOUT))
            .writeTimeout(bounded(writeTimeout, MAX_WRITE_TIMEOUT))
            .callTimeout(bounded(callTimeout, MAX_CALL_TIMEOUT))
            .build();
        this.client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey)
            .httpClient(httpClient).build();
        this.bucket = bucket;
        this.metrics = metrics;
    }

    /** 供存储适配器单元测试注入 MinIO 客户端；生产构造器仍负责超时约束。 */
    MinioPrivateObjectStorage(MinioClient client, String bucket, CampusMetrics metrics) {
        this.client = Objects.requireNonNull(client, "MinIO 客户端不能为空");
        this.bucket = Objects.requireNonNull(bucket, "存储桶不能为空");
        this.metrics = metrics;
    }

    private static Duration bounded(Duration configured, Duration maximum) {
        if (configured == null || configured.isZero() || configured.isNegative()) {
            throw new IllegalArgumentException("对象存储超时必须为正数");
        }
        if (configured.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("对象存储超时超过允许上限");
        }
        return configured;
    }

    @Override
    public void put(String objectKey, InputStream content, long sizeBytes, String contentType) {
        long started = System.nanoTime();
        try {
            ensureBucket();
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey)
                .stream(content, sizeBytes, -1).contentType(contentType).build());
            record("SUCCESS", "UPLOAD", started);
        } catch (Exception e) {
            record("FAILURE", "UPLOAD", started);
            throw new StorageUnavailableException("对象存储不可用", e);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        long started = System.nanoTime();
        try {
            InputStream result = client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return new ObservedReadStream(result, started);
        } catch (ErrorResponseException e) {
            record("FAILURE", "READ", started);
            if ("NoSuchKey".equals(e.errorResponse().code()) || "NoSuchObject".equals(e.errorResponse().code())) {
                throw new ObjectNotFoundException("媒体不存在");
            }
            throw new StorageUnavailableException("对象存储不可用", e);
        } catch (Exception e) {
            record("FAILURE", "READ", started);
            throw new StorageUnavailableException("对象存储不可用", e);
        }
    }

    private final class ObservedReadStream extends FilterInputStream {
        private final long started;
        private final AtomicBoolean measured = new AtomicBoolean();

        private ObservedReadStream(InputStream delegate, long started) {
            super(Objects.requireNonNull(delegate, "MinIO 返回流不能为空"));
            this.started = started;
        }

        @Override
        public int read() throws IOException {
            try {
                int value = in.read();
                if (value < 0) measure("SUCCESS");
                return value;
            } catch (IOException | RuntimeException failure) {
                measure("FAILURE");
                throw failure;
            }
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            try {
                int value = in.read(bytes, offset, length);
                if (value < 0) measure("SUCCESS");
                return value;
            } catch (IOException | RuntimeException failure) {
                measure("FAILURE");
                throw failure;
            }
        }

        @Override
        public void close() throws IOException {
            try {
                in.close();
                measure("SUCCESS");
            } catch (IOException | RuntimeException failure) {
                measure("FAILURE");
                throw failure;
            }
        }

        private void measure(String result) {
            if (measured.compareAndSet(false, true)) record(result, "READ", started);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code()) || "NoSuchObject".equals(e.errorResponse().code())) {
                return;
            }
            throw new StorageUnavailableException("对象存储不可用", e);
        } catch (Exception e) {
            throw new StorageUnavailableException("对象存储不可用", e);
        }
    }

    private void record(String result, String operation, long started) {
        if (metrics == null) return;
        metrics.recordStorage(result);
        metrics.recordOperationDuration(operation, Duration.ofNanos(System.nanoTime() - started));
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
