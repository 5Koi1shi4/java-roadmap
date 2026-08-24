package com.example.search.application.sync;

import com.example.search.domain.ProductSearchSnapshot;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** A versioned, transport-neutral product index mutation. */
public record IndexMutation(long productId, long sourceVersion, Map<String, Object> document) {
    public IndexMutation {
        if (productId <= 0) throw new IllegalArgumentException("productId must be positive");
        if (sourceVersion <= 0) throw new IllegalArgumentException("sourceVersion must be positive");
        if (document == null || document.isEmpty()) throw new IllegalArgumentException("document is required");
        document = Map.copyOf(document);
    }

    public static IndexMutation from(ProductSearchSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot is required");
        return snapshot.status().name().equals("DELETED") ? tombstone(snapshot.productId(), snapshot.sourceVersion())
                : document(snapshot.productId(), snapshot.sourceVersion(), snapshot.name(), snapshot.status().name(),
                snapshot.subtitle(), snapshot.description(), snapshot.categoryCode(), snapshot.categoryName(),
                snapshot.price(), snapshot.createdAt().toString(), snapshot.updatedAt().toString());
    }

    public static IndexMutation upsert(ProductSearchSnapshot snapshot) {
        return from(snapshot);
    }

    public static IndexMutation upsert(long productId, long sourceVersion, Map<String, Object> document) {
        return new IndexMutation(productId, sourceVersion, document);
    }

    public static IndexMutation document(long productId, long sourceVersion, String name, String status) {
        return document(productId, sourceVersion, name, status, null, null, null, null, null, null, null);
    }

    public static IndexMutation document(long productId, long sourceVersion, String name, String status,
                                         String subtitle, String description, String categoryCode,
                                         String categoryName, BigDecimal price, String createdAt, String updatedAt) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("productId", productId);
        document.put("sourceVersion", sourceVersion);
        document.put("name", name);
        document.put("status", status);
        if (subtitle != null) document.put("subtitle", subtitle);
        if (description != null) document.put("description", description);
        if (categoryCode != null) document.put("categoryCode", categoryCode);
        if (categoryName != null) document.put("categoryName", categoryName);
        if (price != null) document.put("price", price);
        if (createdAt != null) document.put("createdAt", createdAt);
        if (updatedAt != null) document.put("updatedAt", updatedAt);
        return new IndexMutation(productId, sourceVersion, document);
    }

    public static IndexMutation tombstone(long productId, long sourceVersion) {
        return new IndexMutation(productId, sourceVersion,
                Map.of("productId", productId, "sourceVersion", sourceVersion, "status", "DELETED"));
    }

    public static IndexMutation tombstone(ProductSearchSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot is required");
        return tombstone(snapshot.productId(), snapshot.sourceVersion());
    }

    public static IndexMutation delete(long productId, long sourceVersion) {
        return tombstone(productId, sourceVersion);
    }
}
