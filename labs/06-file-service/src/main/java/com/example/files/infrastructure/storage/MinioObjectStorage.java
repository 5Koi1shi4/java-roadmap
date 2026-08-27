package com.example.files.infrastructure.storage;

import com.example.files.application.upload.ObjectStorage;
import com.example.files.application.upload.StorageObjectMetadata;
import com.example.files.application.upload.StorageObjectNotFoundException;
import com.example.files.application.upload.TemporaryObject;
import com.example.files.application.upload.UploadRejectedException;
import com.example.files.config.FileServiceProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetBucketLifecycleArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketLifecycleArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.Expiration;
import io.minio.messages.LifecycleConfiguration;
import io.minio.messages.LifecycleRule;
import io.minio.messages.RuleFilter;
import io.minio.messages.Status;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** MinIO 对象存储适配器：只接受不透明 UUID key，并保持上传可恢复语义。 */
public final class MinioObjectStorage implements ObjectStorage {
    private static final Pattern KEY = Pattern.compile("(?:tmp|blobs)/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Duration MAX_TTL = Duration.ofMinutes(2);
    // 应用上限是 20 MiB；使用单 part 让 If-None-Match 条件覆盖整个正式 PUT。
    private static final long MINIO_PART_SIZE = 20L * 1024L * 1024L;

    private final MinioClient client;
    private final String bucket;
    private final Duration maxLinkTtl;
    private final StorageFailureClassifier failures = new StorageFailureClassifier();

    public MinioObjectStorage(FileServiceProperties.Storage storage) {
        this(storage, MAX_TTL);
    }

    public MinioObjectStorage(FileServiceProperties.Storage storage, Duration maxLinkTtl) {
        this(buildClient(storage), storage, maxLinkTtl);
    }

    public MinioObjectStorage(MinioClient client, FileServiceProperties.Storage storage) {
        this(client, storage, MAX_TTL);
    }

    public MinioObjectStorage(MinioClient client, FileServiceProperties.Storage storage, Duration maxLinkTtl) {
        if (client == null || storage == null) throw new IllegalArgumentException("MinIO client and storage are required");
        if (!"minio".equals(storage.type())) throw new IllegalArgumentException("storage type must be minio");
        if (maxLinkTtl == null || maxLinkTtl.isZero() || maxLinkTtl.isNegative()
            || maxLinkTtl.getNano() != 0 || maxLinkTtl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("invalid maximum link TTL");
        }
        this.client = client;
        this.bucket = storage.minioBucket();
        this.maxLinkTtl = maxLinkTtl;
    }

    /** 便于独立集成测试使用已构造的 SDK client。 */
    public MinioObjectStorage(MinioClient client, String bucket) {
        if (client == null || bucket == null || bucket.isBlank()) throw new IllegalArgumentException("client and bucket are required");
        if (!bucket.matches("[a-z0-9][a-z0-9.-]{2,62}")) throw new IllegalArgumentException("invalid MinIO bucket");
        this.client = client;
        this.bucket = bucket;
        this.maxLinkTtl = MAX_TTL;
    }

    /** 启动时确保 bucket 存在，并覆盖为仅 tmp/ 的 24 小时生命周期规则。 */
    public void initialize() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            LifecycleRule rule = new LifecycleRule(Status.ENABLED, null,
                new Expiration((ZonedDateTime) null, 1, null), new RuleFilter("tmp/"),
                "temporary-object-expiry", null, null, null);
            client.setBucketLifecycle(SetBucketLifecycleArgs.builder().bucket(bucket)
                .config(new LifecycleConfiguration(List.of(rule))).build());
        } catch (Exception ex) {
            throw failure("initialize-bucket", ex);
        }
    }

    public void initializeBucket() { initialize(); }

    /** 返回当前规则，供运维探针和集成测试确认正式 blobs/ 未被匹配。 */
    public LifecycleConfiguration lifecycle() {
        try {
            return client.getBucketLifecycle(GetBucketLifecycleArgs.builder().bucket(bucket).build());
        } catch (Exception ex) {
            throw failure("read-lifecycle", ex);
        }
    }

    @Override
    public TemporaryObject writeTemporary(String tempKey, InputStream source, long maxBytes) {
        validateKey(tempKey, "tmp");
        if (source == null) throw new IllegalArgumentException("source must not be null");
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        LimitedInputStream limited = new LimitedInputStream(source, maxBytes);
        try {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(tempKey)
                .stream(limited, -1, MINIO_PART_SIZE).contentType("application/octet-stream").build());
            return new TemporaryObject(tempKey, limited.count());
        } catch (Exception ex) {
            if (limited.exceeded()) {
                try { delete(tempKey); } catch (RuntimeException ignored) { }
                throw new UploadRejectedException("FILE_TOO_LARGE", "upload exceeds byte limit");
            }
            throw failure("write-temporary", ex);
        } finally {
            try { source.close(); } catch (IOException ignored) { }
        }
    }

    @Override
    public void commit(String tempKey, String objectKey) {
        validateKey(tempKey, "tmp");
        validateKey(objectKey, "blobs");
        StorageObjectMetadata sourceMetadata = stat(tempKey);
        try {
            // 目标不存在检查必须由服务端原子条件完成，避免 stat→copy 的 TOCTOU 覆盖窗口。
            try (InputStream source = open(tempKey)) {
                client.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey)
                    .headers(Map.of("If-None-Match", "*"))
                    .stream(source, sourceMetadata.size(), MINIO_PART_SIZE)
                    .contentType("application/octet-stream").build());
            }
            try {
                client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(tempKey).build());
            } catch (Exception cleanupFailure) {
                // 正式对象已条件创建成功；UploadService 随后的幂等删除/清理任务负责补偿 temp。
                // 不抛出，避免上层重复 finalize 或把已成功的正式对象误判为提交失败。
            }
        } catch (Exception ex) {
            if (isConflict(ex)) throw new StorageConflictException();
            throw failure("commit", ex);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        validateKey(objectKey, namespace(objectKey));
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (ErrorResponseException ex) {
            if (isNotFound(ex)) throw new StorageObjectNotFoundException("object does not exist");
            throw failure("open", ex);
        } catch (Exception ex) {
            throw failure("open", ex);
        }
    }

    @Override
    public StorageObjectMetadata stat(String objectKey) {
        validateKey(objectKey, namespace(objectKey));
        try {
            StatObjectResponse response = client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            if (response.size() < 0) throw new IllegalStateException("invalid object metadata");
            return new StorageObjectMetadata(response.size());
        } catch (ErrorResponseException ex) {
            if (isNotFound(ex)) throw new StorageObjectNotFoundException("object does not exist");
            throw failure("stat", ex);
        } catch (StorageObjectNotFoundException ex) {
            throw ex;
        } catch (Exception ex) {
            throw failure("stat", ex);
        }
    }

    @Override
    public void delete(String objectKey) {
        validateKey(objectKey, namespace(objectKey));
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (ErrorResponseException ex) {
            if (!isNotFound(ex)) throw failure("delete", ex);
        } catch (Exception ex) {
            throw failure("delete", ex);
        }
    }

    @Override
    public Optional<URI> createPresignedGet(String objectKey, Duration ttl,
                                            Map<String, String> responseHeaders) {
        validateKey(objectKey, "blobs");
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.getNano() != 0 || ttl.compareTo(maxLinkTtl) > 0) {
            throw new IllegalArgumentException("link ttl must be positive and no more than 2 minutes");
        }
        Map<String, String> safeHeaders = safeResponseHeaders(responseHeaders);
        try {
            String url = client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET).bucket(bucket).object(objectKey)
                .expiry((int) ttl.toSeconds()).extraQueryParams(safeHeaders).build());
            return Optional.of(URI.create(url));
        } catch (Exception ex) {
            throw failure("presign", ex);
        }
    }

    public String bucket() { return bucket; }

    /** 清理/集成探针使用的幂等存在性检查，同样严格校验 key。 */
    public boolean exists(String objectKey) {
        validateKey(objectKey, namespace(objectKey));
        try {
            stat(objectKey);
            return true;
        } catch (StorageObjectNotFoundException ignored) {
            return false;
        }
    }

    public static void validateKey(String key, String expectedNamespace) {
        if (key == null || !KEY.matcher(key).matches() || !key.startsWith(expectedNamespace + "/")) {
            throw new IllegalArgumentException("storage key must use tmp/<UUID> or blobs/<UUID>");
        }
    }

    private static String namespace(String key) {
        if (key != null && key.startsWith("tmp/")) return "tmp";
        if (key != null && key.startsWith("blobs/")) return "blobs";
        throw new IllegalArgumentException("storage key must use tmp/<UUID> or blobs/<UUID>");
    }

    private static MinioClient buildClient(FileServiceProperties.Storage storage) {
        if (storage == null || !"minio".equals(storage.type())) throw new IllegalArgumentException(
            "MinIO storage configuration is required");
        try {
            // 通过反射选择 String endpoint，避免 SDK 8.6 的可选 OkHttp overload 污染编译类路径。
            Object builder = MinioClient.class.getMethod("builder").invoke(null);
            builder = builder.getClass().getMethod("endpoint", String.class)
                .invoke(builder, storage.minioEndpoint());
            builder = builder.getClass().getMethod("region", String.class)
                .invoke(builder, storage.minioRegion());
            builder = builder.getClass().getMethod("credentials", String.class, String.class)
                .invoke(builder, storage.minioAccessKey(), storage.minioSecretKey());
            MinioClient client = (MinioClient) builder.getClass().getMethod("build").invoke(builder);
            client.setTimeout(storage.minioConnectTimeout().toMillis(), storage.minioWriteTimeout().toMillis(),
                storage.minioReadTimeout().toMillis());
            return client;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            throw new IllegalArgumentException("invalid MinIO endpoint", ex);
        }
    }

    private StorageUnavailableException failure(String operation, Throwable cause) {
        return new StorageUnavailableException(operation, failures.classify(cause), cause);
    }

    private static boolean isNotFound(ErrorResponseException ex) {
        int status = httpStatus(ex);
        String code = ex.errorResponse() == null ? "" : ex.errorResponse().code();
        return status == 404 || "NoSuchKey".equalsIgnoreCase(code) || "NoSuchObject".equalsIgnoreCase(code)
            || "NoSuchBucket".equalsIgnoreCase(code);
    }

    private static boolean isConflict(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ErrorResponseException response) {
                int status = httpStatus(response);
                String code = response.errorResponse() == null ? "" : response.errorResponse().code();
                if (status == 409 || status == 412 || "PreconditionFailed".equalsIgnoreCase(code)
                    || "ConditionalRequestConflict".equalsIgnoreCase(code)) return true;
            }
        }
        return false;
    }

    private static int httpStatus(ErrorResponseException exception) {
        try {
            Object response = ErrorResponseException.class.getMethod("response").invoke(exception);
            return response == null ? -1 : (int) response.getClass().getMethod("code").invoke(response);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return -1;
        }
    }

    private static Map<String, String> safeResponseHeaders(Map<String, String> input) {
        if (input == null || input.isEmpty()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : input.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            String key = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            if (!(key.equals("content-type") || key.equals("content-disposition")
                || key.equals("response-content-type") || key.equals("response-content-disposition"))) continue;
            if (entry.getValue().indexOf('\r') >= 0 || entry.getValue().indexOf('\n') >= 0
                || entry.getValue().length() > 1024) throw new IllegalArgumentException("unsafe response header");
            result.put(key.startsWith("response-") ? key : "response-" + key, entry.getValue());
        }
        return Map.copyOf(result);
    }

    private static final class LimitedInputStream extends InputStream {
        private final InputStream delegate;
        private final long max;
        private long count;
        private boolean exceeded;
        private LimitedInputStream(InputStream delegate, long max) { this.delegate = delegate; this.max = max; }
        long count() { return count; }
        boolean exceeded() { return exceeded; }
        @Override public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) add(1);
            return value;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int value = delegate.read(b, off, len);
            if (value > 0) add(value);
            return value;
        }
        private void add(int value) throws IOException {
            if (count > max - value) { exceeded = true; throw new IOException("upload exceeds byte limit"); }
            count += value;
        }
        @Override public void close() { }
    }
}
