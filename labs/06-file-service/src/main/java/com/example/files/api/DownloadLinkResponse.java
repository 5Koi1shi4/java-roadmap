package com.example.files.api;

import java.time.Instant;

/** Response intentionally contains only a relative application link and expiry. */
public record DownloadLinkResponse(String url, Instant expiresAt) {
    public DownloadLinkResponse {
        if (url == null || url.isBlank() || !url.startsWith("/api/local-downloads/") || expiresAt == null) {
            throw new IllegalArgumentException("invalid download link response");
        }
    }
}
