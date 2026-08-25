package com.example.search.application.search;

import com.example.search.domain.ProductStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SearchProductHit(long id, String name, String subtitle, String description, String categoryCode,
                               String categoryName, BigDecimal price, ProductStatus status, long version,
                               Instant createdAt, Instant updatedAt, Map<String, List<String>> highlights) {
    public SearchProductHit {
        if (id <= 0 || name == null || status == null) throw new IllegalArgumentException("invalid search hit");
        highlights = highlights == null ? Map.of() : Map.copyOf(highlights);
    }
}
