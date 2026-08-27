package com.example.files.api;

import com.example.files.api.security.RequesterIdentity;
import com.example.files.api.security.RequesterIdentityResolver;
import com.example.files.api.security.RequesterUnauthenticatedException;
import com.example.files.application.access.DownloadDescriptor;
import com.example.files.application.access.DownloadFailureReason;
import com.example.files.application.access.DownloadService;
import com.example.files.application.audit.CorrelationId;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.context.request.NativeWebRequest;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 流式下载与身份绑定本地链接的 HTTP 边界。 */
@RestController
@RequestMapping
public final class DownloadController {
    private static final Logger LOG = LoggerFactory.getLogger(DownloadController.class);
    private final DownloadService downloads;
    private final RequesterIdentityResolver identities;

    public DownloadController(DownloadService downloads, RequesterIdentityResolver identities) {
        this.downloads = java.util.Objects.requireNonNull(downloads, "downloads");
        this.identities = java.util.Objects.requireNonNull(identities, "identities");
    }

    @GetMapping("/api/files/{fileId}/content")
    public ResponseEntity<StreamingResponseBody> content(@PathVariable String fileId,
                                                         HttpServletRequest request) {
        RequesterIdentity identity = identity(request);
        UUID id = UUID.fromString(fileId);
        DownloadDescriptor descriptor = downloads.authorizeDownload(identity.userId(), id, correlationId(request));
        return responseForDescriptor(descriptor, identity.userId(), id, request);
    }

    @PostMapping(path = "/api/files/{fileId}/download-links", produces = "application/json;charset=UTF-8")
    public ResponseEntity<DownloadLinkResponse> issueLink(@PathVariable String fileId,
                                                           @RequestParam(name = "ttlSeconds", required = false) String ttlSeconds,
                                                           @RequestBody(required = false) Map<String, Object> body,
                                                           HttpServletRequest request) {
        RequesterIdentity identity = identity(request);
        UUID id = UUID.fromString(fileId);
        Duration ttl = ttlSeconds == null && (body == null || body.get("ttlSeconds") == null)
            ? downloads.defaultLinkTtl() : Duration.ofSeconds(ttlSeconds == null ? bodySeconds(body) : strictSeconds(ttlSeconds));
        DownloadService.DownloadLink link = downloads.issueLink(identity.userId(), id, ttl, correlationId(request));
        return ResponseEntity.ok().contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
            .body(new DownloadLinkResponse(link.url(), link.expiresAt()));
    }

    @GetMapping("/api/local-downloads/{token}")
    public ResponseEntity<StreamingResponseBody> redeem(@PathVariable String token, HttpServletRequest request) {
        RequesterIdentity identity = identity(request);
        DownloadDescriptor descriptor = downloads.redeem(token, identity.userId(), correlationId(request));
        return responseForDescriptor(descriptor, identity.userId(), descriptor.fileId(), request);
    }

    private RequesterIdentity identity(HttpServletRequest request) {
        return identities.resolve(request).orElseThrow(RequesterUnauthenticatedException::new);
    }

    private static CorrelationId correlationId(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        return value instanceof CorrelationId id ? id : CorrelationId.random();
    }

    private HttpHeaders downloadHeaders(DownloadDescriptor descriptor) {
        HttpHeaders headers = new HttpHeaders();
        try { headers.setContentType(MediaType.parseMediaType(descriptor.mediaType())); }
        catch (IllegalArgumentException ex) { headers.setContentType(MediaType.APPLICATION_OCTET_STREAM); }
        headers.setContentLength(descriptor.size());
        String display = com.example.files.domain.SafeDisplayName.from(descriptor.displayName()).value();
        String fallback = com.example.files.domain.SafeDisplayName.from(display).asciiFallback().replace("\"", "_");
        String encoded = URLEncoder.encode(display, StandardCharsets.UTF_8).replace("+", "%20")
            .replace("%7E", "~");
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fallback
            + "\"; filename*=UTF-8''" + encoded);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.setCacheControl("private, no-store");
        return headers;
    }

    private ResponseEntity<StreamingResponseBody> responseForDescriptor(DownloadDescriptor descriptor,
                                                                          long actorId, UUID fileId,
                                                                          HttpServletRequest request) {
        HttpHeaders headers;
        try {
            headers = downloadHeaders(descriptor);
        } catch (RuntimeException ex) {
            closeAfterFailure(descriptor, ex, actorId, fileId, request, "HEADER_FAILED");
            recordFailure(actorId, fileId, request, "HEADER_FAILED");
            throw ex;
        }
        AtomicBoolean finished = new AtomicBoolean();
        StreamingResponseBody body = output -> {
            Throwable failure = null;
            try {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = descriptor.content().read(buffer)) != -1) {
                    if (n > 0) output.write(buffer, 0, n);
                }
                output.flush();
            } catch (IOException | RuntimeException ex) {
                failure = ex;
            }
            try {
                descriptor.close();
            } catch (IOException | RuntimeException ex) {
                if (failure == null) {
                    failure = ex;
                    logCloseFailure(actorId, fileId, request, "STREAM_FAILED");
                } else {
                    suppress(failure, ex);
                    logCloseFailure(actorId, fileId, request, "STREAM_FAILED");
                }
            }
            if (failure != null) {
                if (finished.compareAndSet(false, true)) {
                    recordFailure(actorId, fileId, request, "STREAM_FAILED");
                }
                if (failure instanceof IOException io) throw io;
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(failure);
            }
            // 终态只能由一个回调认领；异步超时已先认领时禁止写入 COMPLETED。
            if (!finished.compareAndSet(false, true)) return;
            try {
                downloads.recordCompleted(actorId, fileId, correlationId(request));
            } catch (RuntimeException ex) {
                // 成功审计失败时由同一终态所有者补写失败审计和失败指标。
                recordFailure(actorId, fileId, request, "STREAM_FAILED");
                throw ex;
            }
        };
        if (request != null) {
            try {
                WebAsyncUtils.getAsyncManager(request).registerCallableInterceptor("download-close-" + fileId,
                    new CallableProcessingInterceptor() {
                        private void abort() {
                            if (finished.compareAndSet(false, true)) {
                                try {
                                    descriptor.close();
                                } catch (IOException | RuntimeException closeFailure) {
                                    logCloseFailure(actorId, fileId, request, "ASYNC_ABORTED");
                                }
                                recordFailure(actorId, fileId, request, "ASYNC_ABORTED");
                            }
                        }

                        @Override public <T> Object handleTimeout(NativeWebRequest ignored, Callable<T> task) {
                            abort();
                            return RESULT_NONE;
                        }

                        @Override public <T> Object handleError(NativeWebRequest ignored, Callable<T> task,
                                                                 Throwable failure) {
                            abort();
                            return RESULT_NONE;
                        }

                        @Override public <T> void afterCompletion(NativeWebRequest ignored, Callable<T> task) {
                            abort();
                        }
                    });
            } catch (RuntimeException ex) {
                if (finished.compareAndSet(false, true)) {
                    try {
                        descriptor.close();
                    } catch (IOException | RuntimeException closeFailure) {
                        logCloseFailure(actorId, fileId, request, "ASYNC_ABORTED");
                        suppress(ex, closeFailure);
                    }
                    recordFailure(actorId, fileId, request, "ASYNC_ABORTED");
                }
                throw ex;
            }
        }
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private void closeAfterFailure(DownloadDescriptor descriptor, Throwable original, long actorId,
                                   UUID fileId, HttpServletRequest request, String failureCode) {
        try {
            descriptor.close();
        } catch (IOException | RuntimeException closeFailure) {
            suppress(original, closeFailure);
            logCloseFailure(actorId, fileId, request, failureCode);
        }
    }

    private static void suppress(Throwable original, Throwable additional) {
        try {
            original.addSuppressed(additional);
        } catch (RuntimeException ignoredSuppressionFailure) {
            // 关闭异常不得覆盖原始异常；日志仍只输出固定失败分类。
        }
    }

    private void logCloseFailure(long actorId, UUID fileId, HttpServletRequest request, String failureCode) {
        LOG.warn("download descriptor close failed correlationId={} actorId={} fileId={} failureCode={}",
            correlationId(request).value(), actorId, fileId, DownloadFailureReason.fromCode(failureCode).name());
    }

    private static long bodySeconds(Map<String, Object> body) {
        if (body == null || body.isEmpty() || body.get("ttlSeconds") == null) {
            throw new IllegalArgumentException("ttlSeconds is required");
        }
        Object value = body.get("ttlSeconds");
        if (value instanceof Number number) {
            if (number instanceof Byte || number instanceof Short || number instanceof Integer || number instanceof Long) {
                return number.longValue();
            }
            throw new IllegalArgumentException("ttlSeconds must be an integer");
        }
        try { return Long.parseLong(value.toString()); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("invalid ttlSeconds"); }
    }

    private void recordFailure(long actorId, UUID fileId, HttpServletRequest request, String code) {
        String safeCode = DownloadFailureReason.fromCode(code).name();
        try {
            downloads.recordFailed(actorId, fileId, correlationId(request), safeCode);
        } catch (RuntimeException ex) {
            LOG.warn("download failure audit unavailable correlationId={} fileId={} failureCode={}",
                correlationId(request).value(), fileId, safeCode);
        }
    }

    private static long strictSeconds(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("ttlSeconds must be a positive integer");
        }
        try { return Long.parseLong(value); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("invalid ttlSeconds"); }
    }
}
