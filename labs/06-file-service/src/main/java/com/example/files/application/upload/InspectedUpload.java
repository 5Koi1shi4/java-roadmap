package com.example.files.application.upload;

import com.example.files.domain.DetectedFileType;
import com.example.files.domain.SafeDisplayName;

/** Results of the bounded, single-pass upload inspection. */
public record InspectedUpload(
    SafeDisplayName originalName,
    String declaredType,
    DetectedFileType detectedType,
    long actualSize,
    String contentHash,
    TemporaryObject temporaryObject) {

    public InspectedUpload {
        if (originalName == null || declaredType == null || declaredType.isBlank()
            || detectedType == null || contentHash == null || contentHash.isBlank()
            || temporaryObject == null || actualSize < 0) {
            throw new IllegalArgumentException("inspected upload contains invalid metadata");
        }
    }

    public SafeDisplayName name() { return originalName; }
    public String mediaType() { return detectedType.mediaType(); }
    public String sha256() { return contentHash; }
    public long size() { return actualSize; }
    public String objectKey() { return temporaryObject.key(); }
}
