package com.example.files.api;

import java.time.Instant;

/** 响应只包含相对应用链接和过期时间。 */
public record DownloadLinkResponse(String url, Instant expiresAt) {
    public DownloadLinkResponse {
        if (url == null || url.isBlank() || !url.startsWith("/api/local-downloads/") || expiresAt == null) {
            throw new IllegalArgumentException("invalid download link response");
        }
    }
}
