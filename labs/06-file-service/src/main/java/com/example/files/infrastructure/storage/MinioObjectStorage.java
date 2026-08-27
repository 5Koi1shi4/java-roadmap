package com.example.files.infrastructure.storage;

import com.example.files.application.upload.ObjectStorage;
import com.example.files.application.upload.StorageObjectMetadata;
import com.example.files.application.upload.StorageObjectNotFoundException;
import com.example.files.application.upload.TemporaryObject;
import com.example.files.application.upload.UploadRejectedException;
import com.example.files.config.FileServiceProperties;
import io.minio.BucketExistsArgs;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** MinIO 对象存储适配器：只接受不透明 UUID key，并保持上传可恢复语义。 */
public final class MinioObjectStorage implements ObjectStorage {
    private static final Pattern KEY = Pattern.compile("(?:tmp|blobs)/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Duration MAX_TTL = Duration.ofMinutes(2);
    private static final long MINIO_PART_SIZE = 10L * 1024L * 1024L;

    private final MinioClient client;
    private final String bucket;
    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
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
        this.endpoint = storage.minioEndpoint();
        this.accessKey = storage.minioAccessKey();
        this.secretKey = storage.minioSecretKey();
        this.maxLinkTtl = maxLinkTtl;
    }

    /** 便于独立集成测试使用已构造的 SDK client。 */
    public MinioObjectStorage(MinioClient client, String bucket) {
        if (client == null || bucket == null || bucket.isBlank()) throw new IllegalArgumentException("client and bucket are required");
        if (!bucket.matches("[a-z0-9][a-z0-9.-]{2,62}")) throw new IllegalArgumentException("invalid MinIO bucket");
        this.client = client;
        this.bucket = bucket;
        this.endpoint = null;
        this.accessKey = null;
        this.secretKey = null;
        this.maxLinkTtl = MAX_TTL;
    }

    /** 启动时确保 bucket 存在，并覆盖为仅 tmp/ 的 24 小时生命周期规则。 */
    public void initialize() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            LifecycleRule rule = new LifecycleRule(Status.ENABLED, null,
                new Expiration((ZonedDateTime) null, 1, false), new RuleFilter("tmp/"),
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
        try {
            try {
                client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
                throw new IllegalStateException("destination object already exists");
            } catch (ErrorResponseException ex) {
                if (!isNotFound(ex)) throw ex;
            }
            client.copyObject(CopyObjectArgs.builder().bucket(bucket).object(objectKey)
                .source(CopySource.builder().bucket(bucket).object(tempKey).build()).build());
            try {
                client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(tempKey).build());
            } catch (Exception cleanupFailure) {
                // Copy 已经是正式提交；调用方将该可补偿错误交给清理/恢复状态机。
                throw new StorageCleanupException(cleanupFailure);
            }
        } catch (StorageCleanupException ex) {
            throw ex;
        } catch (Exception ex) {
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
                .expiry((int) ttl.toSeconds()).build());
            // SDK 8.6.0 没有 extraQueryParams；对无响应头场景直接使用其签名。
            // 需要响应头时使用同样凭据重新计算 SigV4，保证 query 仍被签名。
            if (!safeHeaders.isEmpty() && endpoint != null && accessKey != null && secretKey != null) {
                return Optional.of(URI.create(presignWithResponseHeaders(objectKey, ttl, safeHeaders)));
            }
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
        if (storage == null || !"minio".equals(storage.type())) throw new IllegalArgumentException("MinIO storage configuration is required");
        try {
            // 通过反射选择 String endpoint，避免 SDK 8.6 的可选 OkHttp overload 污染编译类路径。
            Object builder = MinioClient.class.getMethod("builder").invoke(null);
            builder = builder.getClass().getMethod("endpoint", String.class)
                .invoke(builder, storage.minioEndpoint());
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

    private String presignWithResponseHeaders(String objectKey, Duration ttl, Map<String, String> headers) {
        // MinIO 默认 region 为 us-east-1；路径采用稳定 path-style，适用于 compose/Testcontainers。
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
        String date = utc.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        String amzDate = utc.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
        String credential = accessKey + "/" + date + "/us-east-1/s3/aws4_request";
        Map<String, String> query = new LinkedHashMap<>();
        query.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        query.put("X-Amz-Credential", credential);
        query.put("X-Amz-Date", amzDate);
        query.put("X-Amz-Expires", Long.toString(ttl.toSeconds()));
        query.put("X-Amz-SignedHeaders", "host");
        query.putAll(headers);
        String canonicalQuery = query.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).reduce((a, b) -> a + "&" + b).orElse("");
        URI base = URI.create(endpoint);
        String path = "/" + bucket + "/" + encodePath(objectKey);
        String host = base.getHost() + (base.getPort() > 0 ? ":" + base.getPort() : "");
        String canonicalRequest = "GET\n" + path + "\n" + canonicalQuery + "\nhost:" + host + "\n\nhost\nUNSIGNED-PAYLOAD";
        String scope = date + "/us-east-1/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        byte[] signingKey = hmac(hmac(hmac(hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date), "us-east-1"), "s3"), "aws4_request");
        query.put("X-Amz-Signature", hex(hmac(signingKey, stringToSign)));
        String signedQuery = query.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).reduce((a, b) -> a + "&" + b).orElse("");
        return base.getScheme() + "://" + host + path + "?" + signedQuery;
    }

    private static String encodePath(String value) {
        return java.util.Arrays.stream(value.split("/", -1)).map(MinioObjectStorage::encode)
            .reduce((a, b) -> a + "/" + b).orElse("");
    }
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%7E", "~");
    }
    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (Exception ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }
    private static byte[] hmac(byte[] key, String value) { return hmac(key, value.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] hmac(byte[] key, byte[] value) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (Exception ex) { throw new IllegalStateException("HMAC unavailable", ex); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) result.append(String.format("%02x", b));
        return result.toString();
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
