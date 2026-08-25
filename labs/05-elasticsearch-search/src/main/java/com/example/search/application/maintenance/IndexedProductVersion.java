package com.example.search.application.maintenance;

/** Version and status observed in a rebuilt Elasticsearch document. */
public record IndexedProductVersion(long productId, long sourceVersion, String status) {
    public IndexedProductVersion {
        if (productId <= 0 || sourceVersion <= 0 || status == null || status.isBlank()) {
            throw new IllegalArgumentException("invalid indexed product version");
        }
    }
}
