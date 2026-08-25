package com.example.search.api;

import com.example.search.application.search.ProductSearchResult;
import com.example.search.domain.ProductStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ProductSearchResponse(List<Item> items, long total, List<Bucket> categories) {
    static ProductSearchResponse from(ProductSearchResult result) {
        return new ProductSearchResponse(result.items().stream().map(hit -> new Item(hit.id(), hit.name(),
                        hit.subtitle(), hit.description(), hit.categoryCode(), hit.categoryName(), hit.price(),
                        hit.status(), hit.version(), hit.createdAt(), hit.updatedAt(), hit.highlights())).toList(),
                result.total(), result.categories().stream()
                        .map(bucket -> new Bucket(bucket.code(), bucket.name(), bucket.count())).toList());
    }

    public record Item(long id, String name, String subtitle, String description, String categoryCode,
                       String categoryName, BigDecimal price, ProductStatus status, long version,
                       Instant createdAt, Instant updatedAt, Map<String, List<String>> highlights) { }

    public record Bucket(String code, String name, long count) { }
}
