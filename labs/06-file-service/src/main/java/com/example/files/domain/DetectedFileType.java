package com.example.files.domain;

import java.util.Locale;
import java.util.Set;

/** The small, explicit media-type allow-list accepted by the service. */
public enum DetectedFileType {
    JPEG("image/jpeg", Set.of("jpg", "jpeg")),
    PNG("image/png", Set.of("png")),
    WEBP("image/webp", Set.of("webp")),
    PDF("application/pdf", Set.of("pdf"));

    private final String mediaType;
    private final Set<String> extensions;

    DetectedFileType(String mediaType, Set<String> extensions) {
        this.mediaType = mediaType;
        this.extensions = extensions;
    }

    public String mediaType() {
        return mediaType;
    }

    public Set<String> extensions() {
        return extensions;
    }

    public boolean acceptsExtension(String extension) {
        return extension != null && extensions.contains(extension.toLowerCase(Locale.ROOT));
    }
}
