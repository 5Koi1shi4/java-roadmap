package com.example.files.api;

import com.example.files.api.security.RequesterIdentity;
import com.example.files.api.security.RequesterIdentityResolver;
import com.example.files.api.security.RequesterUnauthenticatedException;
import com.example.files.application.access.DownloadDescriptor;
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

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Streaming download and actor-bound local link HTTP boundary. */
@RestController
@RequestMapping
public final class DownloadController {
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
        HttpHeaders headers = downloadHeaders(descriptor);
        StreamingResponseBody body = output -> {
            boolean completed = false;
            try (descriptor) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = descriptor.content().read(buffer)) != -1) {
                    if (n > 0) output.write(buffer, 0, n);
                }
                output.flush();
                completed = true;
                downloads.recordCompleted(identity.userId(), id, correlationId(request));
            } catch (IOException | RuntimeException ex) {
                if (!completed) {
                    try {
                        downloads.recordFailed(identity.userId(), id, correlationId(request), "STREAM_FAILED");
                    } catch (RuntimeException ignored) { }
                }
                if (ex instanceof IOException io) throw io;
                throw ex;
            }
        };
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @PostMapping(path = "/api/files/{fileId}/download-links", produces = "application/json;charset=UTF-8")
    public ResponseEntity<DownloadLinkResponse> issueLink(@PathVariable String fileId,
                                                           @RequestParam(name = "ttlSeconds", required = false) Long ttlSeconds,
                                                           @RequestBody(required = false) Map<String, Object> body,
                                                           HttpServletRequest request) {
        RequesterIdentity identity = identity(request);
        UUID id = UUID.fromString(fileId);
        long seconds = ttlSeconds == null ? bodySeconds(body) : ttlSeconds;
        if (seconds <= 0) seconds = 120;
        DownloadService.DownloadLink link = downloads.issueLink(identity.userId(), id,
            Duration.ofSeconds(seconds), correlationId(request));
        return ResponseEntity.ok().contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
            .body(new DownloadLinkResponse(link.url(), link.expiresAt()));
    }

    @GetMapping("/api/local-downloads/{token}")
    public ResponseEntity<StreamingResponseBody> redeem(@PathVariable String token, HttpServletRequest request) {
        RequesterIdentity identity = identity(request);
        UUID tokenFile = null;
        DownloadDescriptor descriptor = downloads.redeem(token, identity.userId(), correlationId(request));
        HttpHeaders headers = downloadHeaders(descriptor);
        StreamingResponseBody body = output -> {
            try (descriptor) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = descriptor.content().read(buffer)) != -1) if (n > 0) output.write(buffer, 0, n);
                output.flush();
                downloads.recordCompleted(identity.userId(), descriptor.fileId(), correlationId(request));
            } catch (IOException | RuntimeException ex) {
                try { downloads.recordFailed(identity.userId(), descriptor.fileId(), correlationId(request), "STREAM_FAILED"); }
                catch (RuntimeException ignored) { }
                if (ex instanceof IOException io) throw io;
                throw ex;
            }
        };
        return ResponseEntity.ok().headers(headers).body(body);
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

    private static long bodySeconds(Map<String, Object> body) {
        if (body == null || body.isEmpty() || body.get("ttlSeconds") == null) return 120;
        Object value = body.get("ttlSeconds");
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(value.toString()); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("invalid ttlSeconds"); }
    }
}
