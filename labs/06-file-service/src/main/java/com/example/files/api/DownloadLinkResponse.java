package com.example.files.api;

import java.time.Instant;

/** 响应只包含相对应用链接和过期时间。 */
public record DownloadLinkResponse(String url, Instant expiresAt) {
    public DownloadLinkResponse {
        boolean local = url != null && url.startsWith("/api/local-downloads/")
            && !url.substring("/api/local-downloads/".length()).contains("/");
        boolean remote = false;
        if (url != null && !local) {
            try {
                java.net.URI parsed = java.net.URI.create(url);
                    remote = ("http".equalsIgnoreCase(parsed.getScheme())
                    || "https".equalsIgnoreCase(parsed.getScheme()))
                    && parsed.getHost() != null && !parsed.getHost().isBlank()
                    && parsed.getUserInfo() == null && parsed.getFragment() == null;
            } catch (IllegalArgumentException ignored) { }
        }
        if (url == null || url.isBlank() || (!local && !remote) || expiresAt == null) {
            throw new IllegalArgumentException("invalid download link response");
        }
    }
}
